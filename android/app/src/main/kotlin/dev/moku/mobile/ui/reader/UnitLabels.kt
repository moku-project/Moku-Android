package dev.moku.mobile.ui.reader

import dev.moku.mobile.backend.BackendUnit
import java.util.Locale

internal val NUMBER_PREFIX = Regex("""^\s*(?:vol(?:ume)?\.?\s*[\d.]+\s*[,:\-–—]?\s*)?(?:chapter|chap|ch|episode|ep)\.?\s*#?\s*-?[\d.]+\s*[:\-–—.]?\s*""", RegexOption.IGNORE_CASE)

internal val NUMBER_IN_TITLE = Regex("""(?:chapter|chap|ch|episode|ep)\.?\s*#?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
internal val INVISIBLE = Regex("""[\s​-‍⁠﻿]+""")

internal fun BackendUnit.num(): Float? =
    number?.takeIf { it >= 0f } ?: title?.let { NUMBER_IN_TITLE.find(it)?.groupValues?.get(1)?.toFloatOrNull() }

internal fun BackendUnit.group(): String = scanlator?.replace(INVISIBLE, " ")?.trim().orEmpty()

internal fun formatNumber(n: Float): String =
    if (n == n.toInt().toFloat()) n.toInt().toString() else "%.2f".format(Locale.US, n).trimEnd('0').trimEnd('.')

internal fun BackendUnit.cleanTitle(): String = title.orEmpty().replace(NUMBER_PREFIX, "").trim()

internal fun BackendUnit.label(word: String): String =
    num()?.let { n -> "$word ${formatNumber(n)}" + cleanTitle().takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty() }
        ?: title?.takeIf { it.isNotBlank() } ?: id
