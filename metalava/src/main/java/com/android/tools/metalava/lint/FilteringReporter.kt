/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava.lint

import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.FilterPredicate
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.SelectableItem
import com.android.tools.metalava.reporter.FileLocation
import com.android.tools.metalava.reporter.Issues
import com.android.tools.metalava.reporter.Reportable
import com.android.tools.metalava.reporter.Reporter
import com.android.tools.metalava.reporter.Severity

/** [Reporter] that filters out items that are not relevant for the current API surface. */
class FilteringReporter(
    private val delegateReporter: Reporter,
    private val oldCodebase: Codebase?,
    private val filterEmit: FilterPredicate
) : Reporter by delegateReporter {
    override fun report(
        id: Issues.Issue,
        reportable: Reportable?,
        message: String,
        location: FileLocation,
        maximumSeverity: Severity,
    ): Boolean {
        var actualMaximumSeverity = maximumSeverity

        val item = reportable as? Item
        if (item != null) {
            // Determine whether to inherit methods from super classes in the previously released
            // API.
            val inherit =
                when (id) {
                    // Do not consider inherited methods in previously released APIs when
                    // determining
                    // the severity to use for the EQUALS_AND_HASH_CODE report as otherwise it will
                    // almost always inherit from `java.lang.Object`. That will break the test as it
                    // expects to see both methods implemented directly on a class, and inherited
                    // methods are explicitly not allowed.
                    Issues.EQUALS_AND_HASH_CODE -> false

                    // Inherit by default.
                    else -> true
                }

            val previousItem = Codebase.findPreviouslyReleased(oldCodebase, item, inherit)

            val computedMaximumSeverity = computeMaximumSeverity(item, previousItem, id)
            if (computedMaximumSeverity == Severity.HIDDEN) {
                return false
            }

            actualMaximumSeverity = minOf(actualMaximumSeverity, computedMaximumSeverity)

            if (item.effectivelyDeprecated && previousItem?.effectivelyDeprecated != false) {
                return false
            }

            val testItem =
                when (item) {
                    is ParameterItem -> item.containingCallable()
                    is SelectableItem -> item
                    else -> error("Unknown item $item")
                }

            if (!filterEmit.test(testItem)) {
                return false
            }
        }

        return delegateReporter.report(id, reportable, message, location, actualMaximumSeverity)
    }

    private var contextItem: Item? = null
    private var maximumSeverityForContextItem: Severity = Severity.UNLIMITED
    private var maximumSeverityForContextItemContents: Severity = Severity.UNLIMITED

    /**
     * Compute the maximum severity of [issue] within [contextItem].
     *
     * @param item is the [Item] on which the issue is being reported.
     * @param previousItem is the corresponding [Item] in the previously released API, `null` if it
     *   could not be found. It may have been inherited from a super class/interface.
     */
    private fun computeMaximumSeverity(item: Item, previousItem: Item?, issue: Issues.Issue) =
        when {
            // FlaggedApi issues are always treated as an error even if they do appear in the
            // previously released API because they are being reported because there is a change
            // compared with the previously released API.
            issue == Issues.UNFLAGGED_API || issue == Issues.UNEXPORTED_FLAGGED_API -> {
                Severity.INFO
            }

            // If the issue is being reported on the [contextItem] then limit this to the maximum
            // severity of that item (which depends on whether that item is in the previously
            // released API or not).
            item === contextItem -> {
                maximumSeverityForContextItem
            }

            // If the context item is hidden but this item is new then treat it as a warning.
            maximumSeverityForContextItem == Severity.HIDDEN && previousItem == null -> {
                Severity.WARNING_ERROR_WHEN_NEW
            }

            // Otherwise, just limit the item to the maximum severity allowed by the context item.
            else -> {
                maximumSeverityForContextItemContents
            }
        }

    internal fun withContext(contextItem: Item, checker: () -> Unit) {
        val oldContextItem = this.contextItem
        val oldMaximumSeverityForItem = this.maximumSeverityForContextItem
        val oldMaximumSeverityForItemContents = this.maximumSeverityForContextItemContents
        try {
            this.contextItem = contextItem
            val previouslyReleased =
                oldCodebase != null && Codebase.wasPreviouslyReleased(oldCodebase, contextItem)
            this.maximumSeverityForContextItem =
                if (previouslyReleased) Severity.HIDDEN else Severity.UNLIMITED
            this.maximumSeverityForContextItemContents = maximumSeverityForContextItem

            checker()
        } finally {
            this.contextItem = oldContextItem
            this.maximumSeverityForContextItem = oldMaximumSeverityForItem
            this.maximumSeverityForContextItemContents = oldMaximumSeverityForItemContents
        }
    }
}
