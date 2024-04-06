/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.streamer.parser.readium

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.InMemoryCacheService
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.publication.services.WebPositionsService
import org.readium.r2.shared.publication.services.cacheServiceFactory
import org.readium.r2.shared.publication.services.locatorServiceFactory
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.asset.ResourceAsset
import org.readium.r2.shared.util.data.CompositeContainer
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.decodeRwpm
import org.readium.r2.shared.util.data.readDecodeOrElse
import org.readium.r2.shared.util.format.FormatSpecification
import org.readium.r2.shared.util.format.LcpSpecification
import org.readium.r2.shared.util.format.RpfSpecification
import org.readium.r2.shared.util.format.RwpmSpecification
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpContainer
import org.readium.r2.shared.util.logging.WarningLogger
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.pdf.PdfDocumentFactory
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.SingleResourceContainer
import org.readium.r2.streamer.parser.PublicationParser
import org.readium.r2.streamer.parser.audio.AudioLocatorService
import timber.log.Timber

/**
 * Parses any Readium Web Publication package or manifest, e.g. WebPub, Audiobook, DiViNa, LCPDF...
 */
public class ReadiumWebPubParser(
    private val context: Context? = null,
    private val httpClient: HttpClient,
    private val pdfFactory: PdfDocumentFactory<*>?
) : PublicationParser {

    override suspend fun parse(
        asset: Asset,
        warnings: WarningLogger?
    ): Try<Publication.Builder, PublicationParser.ParseError> = when (asset) {
        is ResourceAsset -> parseResourceAsset(asset.resource, asset.format.specification)
        is ContainerAsset -> parseContainerAsset(asset.container, asset.format.specification)
    }

    private suspend fun parseContainerAsset(
        container: Container<Resource>,
        formatSpecification: FormatSpecification
    ): Try<Publication.Builder, PublicationParser.ParseError> {
        if (!formatSpecification.conformsTo(RpfSpecification)) {
            return Try.failure(PublicationParser.ParseError.FormatNotSupported())
        }

        val manifestResource = container[Url("manifest.json")!!]
            ?: return Try.failure(
                PublicationParser.ParseError.Reading(
                    ReadError.Decoding(
                        DebugError("Missing manifest.")
                    )
                )
            )

        val manifest = manifestResource
            .readDecodeOrElse(
                decode = { it.decodeRwpm() },
                recover = { return Try.failure(PublicationParser.ParseError.Reading(it)) }
            )

        // Checks the requirements from the LCPDF specification.
        // https://readium.org/lcp-specs/notes/lcp-for-pdf.html
        val readingOrder = manifest.readingOrder
        if (manifest.conformsTo(Publication.Profile.PDF) && formatSpecification.conformsTo(
                LcpSpecification
            ) &&
            (readingOrder.isEmpty() || !readingOrder.all { MediaType.PDF.matches(it.mediaType) })
        ) {
            return Try.failure(
                PublicationParser.ParseError.Reading(
                    ReadError.Decoding("Invalid LCP Protected PDF.")
                )
            )
        }

        val servicesBuilder = Publication.ServicesBuilder().apply {
            cacheServiceFactory = InMemoryCacheService.createFactory(context)

            positionsServiceFactory = when {
                manifest.conformsTo(Publication.Profile.PDF) && formatSpecification.conformsTo(
                    LcpSpecification
                ) ->
                    pdfFactory?.let { LcpdfPositionsService.create(it) }
                manifest.conformsTo(Publication.Profile.DIVINA) ->
                    PerResourcePositionsService.createFactory(MediaType("image/*")!!)
                else ->
                    WebPositionsService.createFactory(httpClient)
            }

            locatorServiceFactory = when {
                manifest.conformsTo(Publication.Profile.AUDIOBOOK) ->
                    AudioLocatorService.createFactory()
                else ->
                    null
            }
        }

        val publicationBuilder = Publication.Builder(manifest, container, servicesBuilder)
        return Try.success(publicationBuilder)
    }

    private suspend fun parseResourceAsset(
        resource: Resource,
        formatSpecification: FormatSpecification
    ): Try<Publication.Builder, PublicationParser.ParseError> {
        if (!formatSpecification.conformsTo(RwpmSpecification)) {
            return Try.failure(PublicationParser.ParseError.FormatNotSupported())
        }

        val manifest = resource
            .readDecodeOrElse(
                decode = { it.decodeRwpm() },
                recover = { return Try.failure(PublicationParser.ParseError.Reading(it)) }
            )

        val baseUrl = manifest.linkWithRel("self")?.href?.resolve()?.toHttpUrl()
        if (baseUrl == null) {
            Timber.w("No valid self link found in the manifest at ${resource.sourceUrl}")
        }

        val resources = (manifest.readingOrder + manifest.resources)
            .map { it.url() }
            .toSet()

        val container =
            CompositeContainer(
                SingleResourceContainer(
                    Url("manifest.json")!!,
                    resource
                ),
                HttpContainer(baseUrl, resources, httpClient)
            )

        return parseContainerAsset(container, FormatSpecification(RpfSpecification))
    }
}
