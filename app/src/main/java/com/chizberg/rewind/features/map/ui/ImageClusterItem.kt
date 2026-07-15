package com.chizberg.rewind.features.map.ui

import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.RgbaColor
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem

/**
 * A single loose image fed to the android-maps-utils overlap clustering (the second clustering
 * layer, mirroring iOS's MapKit `clusteringIdentifier`). Server clusters and grid local clusters
 * are NOT clustered this way — only individual images, which are cheap to render.
 *
 * The render params ([tint]/[foreground]/[angleDeg]) are resolved up front so the marker content is
 * a plain draw. Identity is by cid (matching [ModelImage]) so the cluster manager tracks items
 * across reloads without churn.
 */
class ImageClusterItem(
    val image: ModelImage,
    val tint: RgbaColor,
    val foreground: RgbaColor,
    val angleDeg: Float,
) : ClusterItem {
    private val position = LatLng(image.coordinate.latitude, image.coordinate.longitude)

    override fun getPosition(): LatLng = position

    override fun getTitle(): String = image.title

    override fun getSnippet(): String? = null

    override fun getZIndex(): Float? = null

    override fun equals(other: Any?): Boolean =
        this === other || (other is ImageClusterItem && image.cid == other.image.cid)

    override fun hashCode(): Int = image.cid
}
