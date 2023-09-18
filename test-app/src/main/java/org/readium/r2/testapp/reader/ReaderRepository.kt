/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader

import android.app.Activity
import android.app.Application
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as JetpackPreferences
import org.json.JSONObject
import org.readium.adapters.pdfium.navigator.PdfiumEngineProvider
import org.readium.navigator.web.WebNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.media3.audio.AudioNavigatorFactory
import org.readium.r2.navigator.media3.exoplayer.ExoPlayerEngineProvider
import org.readium.r2.navigator.media3.tts.TtsNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.UserException
import org.readium.r2.shared.asset.AssetRetriever
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.testapp.Readium
import org.readium.r2.testapp.data.BookRepository
import org.readium.r2.testapp.domain.PublicationError
import org.readium.r2.testapp.reader.preferences.AndroidTtsPreferencesManagerFactory
import org.readium.r2.testapp.reader.preferences.EpubPreferencesManagerFactory
import org.readium.r2.testapp.reader.preferences.ExoPlayerPreferencesManagerFactory
import org.readium.r2.testapp.reader.preferences.PdfiumPreferencesManagerFactory
import timber.log.Timber

/**
 * Open and store publications in order for them to be listened or read.
 *
 * Ensure you call [open] before any attempt to start a [ReaderActivity].
 * Pass the method result to the activity to enable it to know which current publication it must
 * retrieve from this repository - media or visual.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderRepository(
    private val application: Application,
    private val readium: Readium,
    private val bookRepository: BookRepository,
    private val preferencesDataStore: DataStore<JetpackPreferences>
) {
    sealed class OpeningError(
        content: Content,
        cause: Exception?
    ) : UserException(content, cause) {

        constructor(@StringRes userMessageId: Int) :
            this(Content(userMessageId), null)

        constructor(cause: UserException) :
            this(Content(cause), cause)

        class PublicationError(
            override val cause: UserException
        ) : OpeningError(cause) {

            companion object {

                operator fun invoke(
                    error: AssetRetriever.Error
                ): OpeningError = PublicationError(
                    org.readium.r2.testapp.domain.PublicationError(
                        error
                    )
                )

                operator fun invoke(
                    error: Publication.OpeningException
                ): OpeningError = PublicationError(
                    org.readium.r2.testapp.domain.PublicationError(
                        error
                    )
                )
            }
        }
    }

    private val repository: MutableMap<Long, ReaderInitData> =
        mutableMapOf()

    private val mediaServiceFacade: MediaServiceFacade =
        MediaServiceFacade(application)

    operator fun get(bookId: Long): ReaderInitData? =
        repository[bookId]

    suspend fun open(bookId: Long, activity: Activity): Try<Unit, OpeningError> {
        if (bookId in repository.keys) {
            return Try.success(Unit)
        }

        val book = checkNotNull(bookRepository.get(bookId)) { "Cannot find book in database." }

        val asset = readium.assetRetriever.retrieve(
            Url(book.href)!!,
            book.mediaType,
            book.assetType
        ).getOrElse { return Try.failure(OpeningError.PublicationError(it)) }

        val publication = readium.publicationFactory.open(
            asset,
            contentProtectionScheme = book.drmScheme,
            allowUserInteraction = true,
            sender = activity
        ).getOrElse { return Try.failure(OpeningError.PublicationError(it)) }

        // The publication is protected with a DRM and not unlocked.
        if (publication.isRestricted) {
            return Try.failure(OpeningError.PublicationError(PublicationError.Forbidden()))
        }

        val initialLocator = book.progression
            ?.let { Locator.fromJSON(JSONObject(it)) }

        val readerInitData = when {
            publication.conformsTo(Publication.Profile.AUDIOBOOK) ->
                openAudio(bookId, publication, initialLocator)
            publication.conformsTo(Publication.Profile.EPUB) ->
                when (publication.metadata.presentation.layout) {
                    EpubLayout.FIXED -> openWeb(bookId, publication, initialLocator)
                    EpubLayout.REFLOWABLE, null -> openWeb(bookId, publication, initialLocator)
                }
            publication.readingOrder.allAreHtml ->
                openEpub(bookId, publication, initialLocator)
            publication.conformsTo(Publication.Profile.PDF) ->
                openPdf(bookId, publication, initialLocator)
            publication.conformsTo(Publication.Profile.DIVINA) ->
                openImage(bookId, publication, initialLocator)
            else ->
                Try.failure(
                    OpeningError.PublicationError(PublicationError.UnsupportedAsset())
                )
        }

        return readerInitData.map { repository[bookId] = it }
    }

    private suspend fun openAudio(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<MediaReaderInitData, OpeningError> {
        val preferencesManager = ExoPlayerPreferencesManagerFactory(preferencesDataStore)
            .createPreferenceManager(bookId)
        val initialPreferences = preferencesManager.preferences.value

        val navigatorFactory = AudioNavigatorFactory(
            publication,
            ExoPlayerEngineProvider(application)
        ) ?: return Try.failure(
            OpeningError.PublicationError(PublicationError.UnsupportedAsset())
        )

        val navigator = navigatorFactory.createNavigator(
            initialLocator,
            initialPreferences
        ) ?: return Try.failure(
            OpeningError.PublicationError(PublicationError.UnsupportedAsset())
        )

        mediaServiceFacade.openSession(bookId, navigator)
        val initData = MediaReaderInitData(
            bookId,
            publication,
            navigator,
            preferencesManager,
            navigatorFactory
        )
        return Try.success(initData)
    }

    private suspend fun openEpub(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<EpubReaderInitData, OpeningError> {
        val preferencesManager = EpubPreferencesManagerFactory(preferencesDataStore)
            .createPreferenceManager(bookId)
        val navigatorFactory = EpubNavigatorFactory(publication)
        val ttsInitData = getTtsInitData(bookId, publication)

        val initData = EpubReaderInitData(
            bookId,
            publication,
            initialLocator,
            preferencesManager,
            navigatorFactory,
            ttsInitData
        )
        return Try.success(initData)
    }

    private suspend fun openWeb(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<WebReaderInitData, OpeningError> {
        val navigatorFactory = org.readium.navigator.web.WebNavigatorFactory(publication)

        val initData = WebReaderInitData(
            bookId,
            publication,
            initialLocator,
            navigatorFactory
        )
        return Try.success(initData)
    }

    private suspend fun openPdf(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<PdfReaderInitData, OpeningError> {
        val preferencesManager = PdfiumPreferencesManagerFactory(preferencesDataStore)
            .createPreferenceManager(bookId)
        val pdfEngine = PdfiumEngineProvider()
        val navigatorFactory = PdfNavigatorFactory(publication, pdfEngine)
        val ttsInitData = getTtsInitData(bookId, publication)

        val initData = PdfReaderInitData(
            bookId,
            publication,
            initialLocator,
            preferencesManager,
            navigatorFactory,
            ttsInitData
        )
        return Try.success(initData)
    }

    private suspend fun openImage(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<ImageReaderInitData, OpeningError> {
        val initData = ImageReaderInitData(
            bookId = bookId,
            publication = publication,
            initialLocation = initialLocator,
            ttsInitData = getTtsInitData(bookId, publication)
        )
        return Try.success(initData)
    }

    private suspend fun getTtsInitData(
        bookId: Long,
        publication: Publication
    ): TtsInitData? {
        val preferencesManager = AndroidTtsPreferencesManagerFactory(preferencesDataStore)
            .createPreferenceManager(bookId)
        val navigatorFactory = TtsNavigatorFactory(
            application,
            publication
        ) ?: return null
        return TtsInitData(mediaServiceFacade, navigatorFactory, preferencesManager)
    }

    suspend fun close(bookId: Long) {
        Timber.v("Closing Publication $bookId.")
        when (val initData = repository.remove(bookId)) {
            is MediaReaderInitData -> {
                mediaServiceFacade.closeSession()
                initData.publication.close()
            }
            is VisualReaderInitData -> {
                mediaServiceFacade.closeSession()
                initData.publication.close()
            }
            null, is DummyReaderInitData -> {
                // Do nothing
            }
        }
    }
}
