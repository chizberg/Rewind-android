package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelLocalCluster

/**
 * A single renderable map annotation. Port of iOS `AnnotationValue`. Divergence: on iOS this keys
 * an imperative `AnnotationStore` of `MKAnnotation`s; here the map renders these directly from
 * state (declarative). Equality delegates to each payload — image by cid, local cluster by id.
 */
sealed interface AnnotationValue {
    data class Image(
        val value: ModelImage,
    ) : AnnotationValue

    data class Cluster(
        val value: ModelCluster,
    ) : AnnotationValue

    data class LocalCluster(
        val value: ModelLocalCluster,
    ) : AnnotationValue

    val image: ModelImage? get() = (this as? Image)?.value
    val cluster: ModelCluster? get() = (this as? Cluster)?.value
    val localCluster: ModelLocalCluster? get() = (this as? LocalCluster)?.value
}
