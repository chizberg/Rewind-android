package com.chizberg.rewind.features.map

/**
 * The result of folding a received batch into the map ([makeDiffAfterReceived]). [state] carries
 * the updated `clusters` + `clusteredImages`; [toAdd]/[toRemove] are the render deltas the
 * annotation layer applies. Port of the iOS `(toAdd:toRemove:)` tuple plus the `inout MapState`
 * mutation.
 */
data class ClusteringDiff(
    val state: MapState,
    val toAdd: List<AnnotationValue>,
    val toRemove: List<AnnotationValue>,
)
