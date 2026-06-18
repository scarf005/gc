package dev.scarf.gc

import org.junit.Assert.assertEquals
import org.junit.Test

class ContributionWidgetLayoutTest {
    @Test
    fun derivesGraphPaddingFromWidgetHeightRatio() {
        assertEquals(9, graphPaddingDp(widgetHeightDp(minHeightDp = 40, maxHeightDp = 110)))
        assertEquals(15, graphPaddingDp(widgetHeightDp(minHeightDp = 80, maxHeightDp = 182)))
    }

    @Test
    fun usesCurrentPortraitWidthInsteadOfLandscapeMaximumForColumnFitting() {
        assertEquals(WidgetGraphBoundsDp(widthDp = 344, heightDp = 92), widgetGraphBoundsDp(minWidthDp = 362, maxWidthDp = 500, heightDp = 110, graphPaddingDp = 9))
        assertEquals(WidgetGraphBoundsDp(widthDp = 232, heightDp = 92), widgetGraphBoundsDp(minWidthDp = 250, maxWidthDp = 500, heightDp = 110, graphPaddingDp = 9))
    }

    @Test
    fun keepsGapAndCapsHorizontalPaddingToVerticalPadding() {
        assertEquals(ContributionGraphLayout(columns = 18, weekBlocks = 1, cellDp = 11, gapDp = 2), contributionGraphLayout(widthDp = 232, heightDp = 92, targetCellDp = 10, targetGapDp = 2))
        assertEquals(ContributionGraphLayout(columns = 21, weekBlocks = 1, cellDp = 10, gapDp = 2), contributionGraphLayout(widthDp = 250, heightDp = 92, targetCellDp = 10, targetGapDp = 2))
        assertEquals(ContributionGraphLayout(columns = 28, weekBlocks = 1, cellDp = 10, gapDp = 2), contributionGraphLayout(widthDp = 344, heightDp = 92, targetCellDp = 10, targetGapDp = 2))
    }

    @Test
    fun scalesCellsUpInsteadOfAddingVerticalWeekBlocks() {
        assertEquals(ContributionGraphLayout(columns = 27, weekBlocks = 1, cellDp = 22, gapDp = 4), contributionGraphLayout(widthDp = 700, heightDp = 182, targetCellDp = 10, targetGapDp = 2))
    }

    @Test
    fun keepsAtLeastOneColumnAndOneWeekBlockForTinyWidgets() {
        assertEquals(ContributionGraphLayout(columns = 1, weekBlocks = 1, cellDp = 1, gapDp = 0), contributionGraphLayout(widthDp = 1, heightDp = 1, targetCellDp = 10, targetGapDp = 2))
    }
}
