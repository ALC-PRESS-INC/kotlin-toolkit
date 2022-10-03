/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.pdf

import android.graphics.PointF
import androidx.fragment.app.Fragment
import org.readium.r2.navigator.settings.Configurable
import org.readium.r2.navigator.settings.Preferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.fetcher.Resource
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression


/** A [PdfEngineProvider] renders a single PDF resource.
*
* To be implemented by third-party PDF engines which can be used with [PdfNavigatorFragment].
*/
@ExperimentalReadiumApi
interface PdfEngineProvider<S: PdfSettings> {

    suspend fun createDocumentFragment(input: PdfDocumentFragmentInput<S>): PdfDocumentFragment<S>

    fun createSettings(metadata: Metadata, preferences: Preferences): S
}

@ExperimentalReadiumApi
typealias PdfDocumentFragmentFactory<S> = suspend (PdfDocumentFragmentInput<S>) -> PdfDocumentFragment<S>

@ExperimentalReadiumApi
abstract class PdfDocumentFragment<S: PdfSettings> : Fragment() {

    interface Listener {
        /**
         * Called when the fragment navigates to a different page.
         */
        fun onPageChanged(pageIndex: Int)

        /**
         * Called when the user triggers a tap on the document.
         */
        fun onTap(point: PointF): Boolean

        /**
         * Called when the PDF engine fails to load the PDF document.
         */
        fun onResourceLoadFailed(link: Link, error: Resource.Exception)
    }

    /**
     * Returns the current page index in the document, from 0.
     */
    abstract val pageIndex: Int

    /**
     * Jumps to the given page [index].
     *
     * @param animated Indicates if the transition should be animated.
     * @return Whether the jump is valid.
     */
    abstract fun goToPageIndex(index: Int, animated: Boolean): Boolean

    /**
     * Current presentation settings for the PDF document.
     */
    abstract var settings: S
}

@ExperimentalReadiumApi
data class PdfDocumentFragmentInput<S: PdfSettings>(
    val publication: Publication,
    val link: Link,
    val initialPageIndex: Int,
    val settings: S,
    val listener: PdfDocumentFragment.Listener?
)

@ExperimentalReadiumApi
interface PdfSettings : Configurable.Settings {

    val readingProgressionValue: ReadingProgression

    val scrollValue: Boolean
}

@ExperimentalReadiumApi
interface PdfSettingsValues