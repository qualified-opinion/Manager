package com.janreins.vaultlock.ui

/** Splits a 2FA code into two readable halves: 123 456, 123 4567, 1234 5678. */
fun groupTotpCode(code: String): String {
    if (code.length < 6) return code
    val split = code.length / 2
    return "${code.take(split)} ${code.drop(split)}"
}
