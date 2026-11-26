package org.fluidmessages

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.util.Locale

/**
 * Settings → About and updates → This app's signing key: the SHA-256 fingerprint of the certificate this copy was
 * signed with, and whether it is the official one published in docs/verify.md.
 */
object SigningKey {
    /** The official FluidMessages signing certificate, SHA-256 (docs/verify.md). Change both together. */
    const val OFFICIAL = "2d0980ee4c11bf655482e7ee9279c6e66b56636e8e18c8ac063e1f08785df5bc"
    const val VERIFY_PAGE = "https://github.com/Lin8x/FluidMessages/blob/main/docs/verify.md"

    /** SHA-256 of a certificate, as 64 lowercase hex characters (the form `apksigner verify --print-certs` prints). */
    @JvmStatic
    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Only the hex digits, lowercase, so any spacing, colons, or case compare the same. */
    @JvmStatic
    fun normalize(value: String): String = value.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.lowercase(Locale.ROOT)

    /** 2D:09:80:… — the form AppVerifier and most guides use, and what Copy puts on the clipboard. */
    @JvmStatic
    fun colons(value: String): String = normalize(value).uppercase(Locale.ROOT).chunked(2).joinToString(":")

    /** The colon form in lines of eight pairs, so it is easy to read and compare by eye. */
    @JvmStatic
    fun grouped(value: String): String = colons(value).split(":").chunked(8).joinToString("\n") { it.joinToString(":") }

    @JvmStatic
    fun matchesOfficial(value: String): Boolean = normalize(value).let { it.length == 64 && it == OFFICIAL }

    enum class Status { OFFICIAL, FDROID, STORE, SOMEONE_ELSE }

    /**
     * What About and updates says. F-Droid may build from source and sign with its own key, so its build says so
     * instead of warning. A store build (the .aab) may be signed by the store, like Google Play's own key. Any other
     * key is someone else's.
     */
    @JvmStatic
    @JvmOverloads
    fun status(value: String, fdroidBuild: Boolean, storeBuild: Boolean = false): Status = when {
        matchesOfficial(value) -> Status.OFFICIAL
        fdroidBuild && normalize(value).length == 64 -> Status.FDROID
        storeBuild && normalize(value).length == 64 -> Status.STORE
        else -> Status.SOMEONE_ELSE
    }

    /** The installed app's current signing certificate fingerprint, or "" if Android doesn't say. */
    @JvmStatic
    fun installed(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        val signers = info?.apkContentsSigners
        if (signers.isNullOrEmpty()) "" else fingerprint(signers[0].toByteArray())
    } catch (_: PackageManager.NameNotFoundException) { "" }
}
