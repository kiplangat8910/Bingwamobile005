package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object OfferRepository {
    private const val PREFS_NAME = "DataOffers"
    private const val KEY_OFFERS = "offers"
    private const val KEY_CATALOG_VERSION = "catalog_version"
    private const val CURRENT_CATALOG_VERSION = 3
    const val ACTION_OFFERS_UPDATED = "com.bingwa.mobile.OFFERS_UPDATED"
    private val gson = Gson()
    @Volatile private var cachedRawJson: String? = null
    @Volatile private var cachedOffers: List<OfferItem>? = null

    data class RepairResult(
        val offers: List<OfferItem>,
        val restoredDefaultOffers: Int,
        val removedBrokenOffers: Int
    )

    fun ensureSeeded(context: Context): List<OfferItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.safeGetString(KEY_OFFERS, null)
        val parsed = parse(rawJson)
        return when {
            rawJson.isNullOrBlank() -> {
                seedDefaults(context, prefs)
            }
            parsed == null -> repair(context).offers
            else -> {
                val cleaned = sanitize(parsed)
                if (prefs.getInt(KEY_CATALOG_VERSION, 0) < CURRENT_CATALOG_VERSION) {
                    migrateCatalog(context, cleaned, prefs)
                } else {
                    cleaned.also { updateCache(rawJson, it) }
                }
            }
        }
    }

    fun load(context: Context): MutableList<OfferItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.safeGetString(KEY_OFFERS, null)
        val cached = cachedOffers
        if (rawJson == cachedRawJson && cached != null) return copyOffers(cached)
        return (parse(rawJson)?.let(::sanitize) ?: mutableListOf()).also { updateCache(rawJson, it) }
    }

    fun save(context: Context, offers: List<OfferItem>) {
        val sanitized = sanitize(offers)
        val json = gson.toJson(sanitized)
        updateCache(json, sanitized)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OFFERS, json)
            .apply()
        context.sendBroadcast(Intent(ACTION_OFFERS_UPDATED).setPackage(context.packageName))
    }

    fun repair(context: Context): RepairResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = parse(prefs.safeGetString(KEY_OFFERS, null)).orEmpty()
        val cleaned = sanitize(current)
        val existingKeys = cleaned.mapTo(mutableSetOf(), ::offerKey)
        val managedPrices = UssdStorage(context).managedCatalogPrices()
        val existingManagedPrices = cleaned.mapTo(mutableSetOf()) { it.price }.intersect(managedPrices)
        val merged = cleaned.toMutableList()
        var nextId = ((merged.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0)) + 1
        var restoredDefaultOffers = 0

        defaultOffers(context).forEach { offer ->
            if (offer.price in existingManagedPrices) return@forEach
            if (existingKeys.add(offerKey(offer))) {
                merged += offer.copy(id = nextId++)
                restoredDefaultOffers++
            }
        }

        val repairedOffers = sanitize(merged).sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name })
        val removedBrokenOffers = current.size - cleaned.size
        save(context, repairedOffers)
        setCatalogVersion(prefs)
        return RepairResult(
            offers = repairedOffers,
            restoredDefaultOffers = restoredDefaultOffers,
            removedBrokenOffers = removedBrokenOffers
        )
    }

    fun findById(context: Context, offerId: Int): OfferItem? =
        load(context).firstOrNull { it.id == offerId }

    fun findByName(context: Context, offerName: String): OfferItem? =
        load(context).firstOrNull { it.enabled && it.name.trim().equals(offerName.trim(), ignoreCase = true) }

    fun findByPrice(context: Context, amount: Int): OfferItem? =
        load(context).firstOrNull { it.enabled && it.price == amount }

    fun updateSignature(
        context: Context,
        offerId: Int,
        signature: List<UssdSignatureStep>,
        learningCaptures: List<UssdLearningCapture> = emptyList()
    ): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val updated = offers[index].copy(
            signatureLearnedAt = System.currentTimeMillis(),
            learnedSignature = signature,
            signatureLearningCaptures = learningCaptures
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    fun stageSignatureReview(
        context: Context,
        offerId: Int,
        signature: List<UssdSignatureStep>,
        learningCaptures: List<UssdLearningCapture> = emptyList()
    ): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val updated = offers[index].copy(
            pendingLearnedSignature = signature,
            pendingSignatureLearningCaptures = learningCaptures,
            pendingSignatureLearnedAt = System.currentTimeMillis()
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    fun approveStagedSignature(context: Context, offerId: Int): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val offer = offers[index]
        if (offer.pendingLearnedSignature.isEmpty() && offer.pendingSignatureLearningCaptures.isEmpty()) return offer
        val approvedAt = if (offer.pendingSignatureLearnedAt > 0L) {
            offer.pendingSignatureLearnedAt
        } else {
            System.currentTimeMillis()
        }
        val updated = offer.copy(
            learnedSignature = offer.pendingLearnedSignature,
            signatureLearningCaptures = offer.pendingSignatureLearningCaptures,
            signatureLearnedAt = approvedAt,
            pendingLearnedSignature = emptyList(),
            pendingSignatureLearningCaptures = emptyList(),
            pendingSignatureLearnedAt = 0L
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    fun clearPendingSignature(context: Context, offerId: Int): OfferItem? {
        val offers = load(context)
        val index = offers.indexOfFirst { it.id == offerId }
        if (index < 0) return null
        val updated = offers[index].copy(
            pendingLearnedSignature = emptyList(),
            pendingSignatureLearningCaptures = emptyList(),
            pendingSignatureLearnedAt = 0L
        )
        offers[index] = updated
        save(context, offers)
        return updated
    }

    private fun parse(rawJson: String?): MutableList<OfferItem>? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            gson.fromJson<MutableList<OfferItem>>(rawJson, object : TypeToken<MutableList<OfferItem>>() {}.type)
        } catch (_: Exception) {
            null
        }
    }

    private fun updateCache(rawJson: String?, offers: List<OfferItem>) {
        cachedRawJson = rawJson
        cachedOffers = offers.map { it.copy() }
    }

    private fun copyOffers(offers: List<OfferItem>): MutableList<OfferItem> =
        offers.map { it.copy() }.toMutableList()

    private fun seedDefaults(context: Context, prefs: SharedPreferences): List<OfferItem> {
        val defaults = defaultOffers(context)
        save(context, defaults)
        setCatalogVersion(prefs)
        return defaults
    }

    private fun migrateCatalog(
        context: Context,
        currentOffers: List<OfferItem>,
        prefs: SharedPreferences
    ): List<OfferItem> {
        val storage = UssdStorage(context)
        val defaults = defaultOffers(context)
        val managedPrices = storage.managedCatalogPrices() + storage.legacyManagedCatalogPrices()
        val existingByPrice = currentOffers.associateBy { it.price }
        val migrated = mutableListOf<OfferItem>()
        var nextId = ((currentOffers.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0)) + 1

        defaults.forEach { default ->
            val existing = existingByPrice[default.price]
            migrated += if (existing != null) {
                // Preserve the user's saved offer exactly as edited instead of
                // overwriting fields like name or USSD code from catalog defaults.
                existing
            } else {
                default.copy(id = nextId++)
            }
        }

        currentOffers
            .filterNot { it.price in managedPrices }
            .forEach { offer ->
                migrated += if (migrated.any { it.id == offer.id }) {
                    offer.copy(id = nextId++)
                } else {
                    offer
                }
            }

        val repaired = sanitize(migrated).sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name })
        save(context, repaired)
        setCatalogVersion(prefs)
        return repaired
    }

    private fun setCatalogVersion(prefs: SharedPreferences) {
        prefs.edit().putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION).apply()
    }

    private fun sanitize(offers: List<OfferItem>): MutableList<OfferItem> {
        val seenIds = mutableSetOf<Int>()
        var nextId = ((offers.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0)) + 1
        val cleaned = mutableListOf<OfferItem>()

        offers.forEach { offer ->
            val trimmedName = offer.name.trim()
            val trimmedCode = offer.ussdCode.trim()
            if (trimmedName.isBlank() || trimmedCode.isBlank() || offer.price <= 0) return@forEach

            val mode = offer.executionMode.takeIf { it == "SIMPLE" || it == "ADVANCED" } ?: "ADVANCED"
            var normalized = offer.copy(
                name = trimmedName,
                ussdCode = trimmedCode,
                executionMode = mode,
                category = offer.category.ifBlank { "Data" },
                targetDevice = offer.targetDevice.ifBlank { "PRIMARY" },
                signatureDetectionEnabled = offer.signatureDetectionEnabled && mode == "ADVANCED"
            )

            if (!seenIds.add(normalized.id)) {
                normalized = normalized.copy(id = nextId++)
                seenIds.add(normalized.id)
            }
            cleaned += normalized
        }
        return cleaned
    }

    private fun defaultOffers(context: Context): List<OfferItem> {
        val storage = UssdStorage(context)
        return storage.getLabels()
            .toList()
            .sortedBy { it.first }
            .mapIndexed { index, (price, label) ->
                OfferItem(
                    id = index,
                    name = label,
                    price = price.toInt(),
                    ussdCode = storage.getUssdForAmount(price).orEmpty()
                )
            }
            .filter { it.ussdCode.isNotBlank() }
    }

    private fun offerKey(offer: OfferItem): String =
        "${offer.name.trim().lowercase()}|${offer.price}"
}
