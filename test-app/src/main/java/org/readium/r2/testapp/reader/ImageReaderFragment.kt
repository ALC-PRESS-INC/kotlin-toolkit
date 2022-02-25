/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.commitNow
import androidx.lifecycle.ViewModelProvider
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.testapp.R

class ImageReaderFragment : VisualReaderFragment(), ImageNavigatorFragment.Listener {

    override lateinit var model: ReaderViewModel
    override lateinit var navigator: VisualNavigator
    private lateinit var publication: Publication

    override fun onCreate(savedInstanceState: Bundle?) {
        ViewModelProvider(requireActivity())[ReaderViewModel::class.java].let {
            model = it
            publication = it.publication
        }

        val readerData = model.readerInitData as VisualReaderInitData

        childFragmentManager.fragmentFactory =
            ImageNavigatorFragment.createFactory(publication, readerData.initialLocation, this)

        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view =  super.onCreateView(inflater, container, savedInstanceState)
        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                add(R.id.fragment_reader_container, ImageNavigatorFragment::class.java, Bundle(), NAVIGATOR_FRAGMENT_TAG)
            }
        }
        navigator = childFragmentManager.findFragmentByTag(NAVIGATOR_FRAGMENT_TAG)!! as VisualNavigator
        return view
    }

    companion object {

        const val NAVIGATOR_FRAGMENT_TAG = "navigator"
    }
}
