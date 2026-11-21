package com.infinifolder.viewer

import org.infiniforge.fx.api.FxRect

/** Center-crop geometry shared by the artwork and GPU presentation paths. */
internal object ArtworkCropGeometry {
    /**
     * Returns the centered normalized source region that fills the destination.
     * For square art in a 16:9 pane this removes 7/32 from each vertical edge,
     * equivalent to fitting the square to pane width with a negative centered Y.
     */
    fun centerCropSourceRect(sourceWidth: Int, sourceHeight: Int, destinationWidth: Int, destinationHeight: Int): FxRect {
        require(sourceWidth > 0 && sourceHeight > 0 && destinationWidth > 0 && destinationHeight > 0)
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val destinationAspect = destinationWidth.toFloat() / destinationHeight
        return if (sourceAspect > destinationAspect) {
            val visibleWidth = destinationAspect / sourceAspect
            val left = (1f - visibleWidth) * 0.5f
            FxRect(left, 0f, left + visibleWidth, 1f)
        } else {
            val visibleHeight = sourceAspect / destinationAspect
            val bottom = (1f - visibleHeight) * 0.5f
            FxRect(0f, bottom, 1f, bottom + visibleHeight)
        }
    }
}
