package io.github.tritium_launcher.launcher.accounts

import com.microsoft.aad.msal4j.*
import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.atomicWrite
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Microsoft Authentication Library impl.
 */
internal object MSAL {
    private val dir = fromTR(TConstants.Dirs.MSAL)
    private const val CLIENT_ID = "6d6b484d-842d-47c9-abe1-e4f0c5f07c77"

    val app: PublicClientApplication

    private val logger = logger()

    init {
        try {
            dir.mkdirs()
        } catch (e: Exception) {
            logger.warn("Could not create MSAL directory at '$dir'", e)
        }

        val cacheFile = dir.resolve("msal_cache.dat")

        val tokenCache = FileTokenCache(cacheFile)

        val builder = PublicClientApplication.builder(CLIENT_ID)
            .authority("https://login.microsoftonline.com/consumers")
            .setTokenCacheAccessAspect(tokenCache)

        app = builder.build()

        try {
            logger.info("MSAL initialized, cache file: ${cacheFile.toAbsolute()}")
            val keyCheck = Keyring.getSecret() != null
            logger.info("Keyring available=${keyCheck}")
        } catch (t: Throwable) {
            logger.warn("MSAL init diagnostics failed", t)
        }
    }

    fun findAccount(homeAccountId: String? = null, username: String? = null): IAccount? {
        val accounts = app.accounts
        val accs = try { accounts.get() } catch (t: Throwable) {
            logger.warn("Could not get Microsoft accounts", t)
            emptySet<IAccount>()
        }
        logger.info("Microsoft Accounts found: ${accs.size}")
        return accs.firstOrNull { a ->
            (homeAccountId != null && a.homeAccountId() == homeAccountId)
                    || (username != null && a.username() == username)
        }
    }

    /**
     * This is set due to having potential conflicts with Qt.
     *
     * When the system's version of Qt on Linux is newer than what Tritium uses, some URL commands will fail silently.
     * [Platform.openBrowser] tries more than one URL command to ensure the browser is opened.
     */
    internal val openBrowserAction = OpenBrowserAction { url ->
        try {
            Platform.openBrowser(url.toString())
        } catch (t: Throwable) {
            throw t
        }
    }

    internal fun systemBrowserOptions() = SystemBrowserOptions.builder()
        .openBrowserAction(openBrowserAction)
        .build()

    private object Keyring {
        private const val SERVICE = "TritiumMSAL"
        private const val ACCOUNT = "TritiumLauncherTokenKey"
        private const val TOOL_TIMEOUT_SECONDS = 5L
        private val FALLBACK_KEY_FILE = fromTR(TConstants.Dirs.MSAL).resolve("msal_key.bin")

        private val logger = logger()

        private data class SecretToolResult(
            val exitCode: Int,
            val output: String
        )

        fun getSecret(): ByteArray? = getSecrets().firstOrNull()

        fun getSecrets(): List<ByteArray> = when(Platform.current) {
            Platform.Linux -> getLinuxSecrets()
//            Platform.MacOSX -> getMacSecret() TODO: Do MacOS and Windows security
            else -> emptyList()
        }

        fun putSecret(secret: ByteArray): Boolean = when(Platform.current) {
            Platform.Linux -> putLinuxSecret(secret)
//            Platform.MacOSX -> putMacSecret() TODO: Do MacOS and Windows security
            else -> false
        }

        private fun commandExists(command: String): Boolean {
            val path = System.getenv("PATH") ?: return false
            for(dir in path.split(':')) {
                if(dir.isBlank()) continue
                val c = Path.of(dir, command)
                if(Files.isRegularFile(c) && Files.isExecutable(c)) return true
            }
            return false
        }

        private fun runSecretTool(args: List<String>, stdin: String? = null): SecretToolResult? {
            val action = if(args.size >= 2) args[1] else "command"
            if(!commandExists("secret-tool")) return null

            return try {
                val p = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()

                if (stdin != null) {
                    p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
                } else {
                    p.outputStream.close()
                }

                val finished = p.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    logger.warn("secret-tool {} timed out after {}s", action, TOOL_TIMEOUT_SECONDS)
                    return null
                }

                val output = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                SecretToolResult(p.exitValue(), output)
            } catch (t: Throwable) {
                logger.warn("Failed to run secret-tool {}", action, t)
                null
            }
        }

        private fun normalizeLinuxSecret(secret: ByteArray?, source: String): ByteArray? {
            if (secret == null) return null
            if (secret.size != 32) {
                logger.warn("Ignoring {} MSAL key with unexpected length (expected 32 bytes)", source)
                return null
            }
            return secret
        }

        private fun addUniqueSecret(
            destination: MutableList<ByteArray>,
            seen: MutableSet<String>,
            secret: ByteArray?,
            source: String
        ) {
            val normalized = normalizeLinuxSecret(secret, source) ?: return
            val fingerprint = Base64.encode(normalized)
            if (seen.add(fingerprint)) {
                destination += normalized
            }
        }

        private fun getFallbackSecret(): ByteArray? {
            val b64 = try {
                FALLBACK_KEY_FILE.readTextOrNull()?.trim()
            } catch (t: Throwable) {
                logger.warn("Failed to read fallback MSAL key file '{}'", FALLBACK_KEY_FILE, t)
                null
            } ?: return null

            if (b64.isBlank()) return null
            return try {
                normalizeLinuxSecret(Base64.decode(b64), "fallback")
            } catch (t: Throwable) {
                logger.warn("Fallback MSAL key file contains invalid base64", t)
                null
            }
        }

        private fun putFallbackSecret(secret: ByteArray): Boolean {
            return try {
                val b64 = Base64.encode(secret)
                atomicWrite(FALLBACK_KEY_FILE, b64.toByteArray(Charsets.UTF_8), durable = true)
                val f = FALLBACK_KEY_FILE.toJFile()
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setExecutable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
                true
            } catch (t: Throwable) {
                logger.warn("Failed to write fallback MSAL key file '{}'", FALLBACK_KEY_FILE, t)
                false
            }
        }

        private fun getLinuxSecrets(): List<ByteArray> {
            val secrets = mutableListOf<ByteArray>()
            val seen = HashSet<String>()

            val result = runSecretTool(
                args = listOf("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT)
            )
            if (result != null && result.exitCode != 0) {
                logger.warn("secret-tool lookup returned {}, no secret present", result.exitCode)
            }
            if (result != null) {
                val b64 = result.output
                if (b64.isNotBlank()) {
                    val secret = try {
                        Base64.decode(b64)
                    } catch (t: Throwable) {
                        logger.warn("secret-tool lookup returned invalid base64 secret", t)
                        null
                    }
                    addUniqueSecret(secrets, seen, secret, "secret-tool")
                }
            }

            addUniqueSecret(secrets, seen, getFallbackSecret(), "fallback")
            return secrets
        }

        private fun putLinuxSecret(secret: ByteArray): Boolean {
            val normalized = normalizeLinuxSecret(secret, "provided") ?: return false
            val fallbackStored = putFallbackSecret(normalized)

            val b64 = Base64.encode(secret)
            val result = runSecretTool(
                args = listOf("secret-tool", "store", "--label", "Tritium MSAL key", "service", SERVICE, "account", ACCOUNT),
                stdin = b64
            )
            if (result != null && result.exitCode != 0) {
                logger.warn("secret-tool store returned {}", result.exitCode)
            }

            val keyringStored = result?.exitCode == 0
            if (!keyringStored && !fallbackStored) {
                logger.warn("Failed to persist MSAL key in both keyring and fallback file")
            }
            return keyringStored || fallbackStored
        }

        fun generateSecret(): ByteArray {
            val r = SecureRandom()
            val key = ByteArray(32)
            r.nextBytes(key)
            return key
        }
    }

    private class FileTokenCache(private val cacheFile: VPath): ITokenCacheAccessAspect {
        private val lock = ReentrantLock()
        private val aesKey = 32
        private val gcmIv = 12
        private val tagBits = 128
        private val magic = "TRMSAL1".toByteArray(Charsets.US_ASCII)
        private val modePlain: Byte = 0
        private val modeEncrypted: Byte = 1
        private var preferredKey: ByteArray? = null

        private fun addUniqueCacheKey(
            destination: MutableList<ByteArray>,
            seen: MutableSet<String>,
            key: ByteArray?,
            source: String
        ) {
            if (key == null) return
            if (key.size != aesKey) {
                logger.warn("Ignoring {} MSAL key with unexpected length (expected {} bytes)", source, aesKey)
                return
            }
            val fingerprint = Base64.encode(key)
            if (seen.add(fingerprint)) {
                destination += key
            }
        }

        private fun candidateKeys(): List<ByteArray> {
            val keys = mutableListOf<ByteArray>()
            val seen = HashSet<String>()
            addUniqueCacheKey(keys, seen, preferredKey, "preferred")
            Keyring.getSecrets().forEach { addUniqueCacheKey(keys, seen, it, "keyring") }
            return keys
        }

        private fun getExistingKey(): ByteArray? {
            val key = candidateKeys().firstOrNull() ?: return null
            preferredKey = key.copyOf()
            return key
        }

        private fun getOrCreateKey(): ByteArray? {
            getExistingKey()?.let { return it }

            val key = Keyring.generateSecret()
            val stored = Keyring.putSecret(key)
            if(!stored) {
                logger.warn("Failed to store key in OS keyring, using plaintext")
                return null
            }
            preferredKey = key.copyOf()
            return key
        }

        private fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
            val iv = ByteArray(gcmIv)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(tagBits, iv)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            val cipherText = cipher.doFinal(plain)
            val out = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
            return out
        }

        private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
            val iv = data.copyOfRange(0, gcmIv)
            val ct = data.copyOfRange(gcmIv, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(tagBits, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            return cipher.doFinal(ct)
        }

        private fun wrapPlain(data: ByteArray): ByteArray {
            val out = ByteArray(magic.size + 1 + data.size)
            System.arraycopy(magic, 0, out, 0, magic.size)
            out[magic.size] = modePlain
            System.arraycopy(data, 0, out, magic.size + 1, data.size)
            return out
        }

        private fun wrapEncrypted(data: ByteArray): ByteArray {
            val out = ByteArray(magic.size + 1 + data.size)
            System.arraycopy(magic, 0, out, 0, magic.size)
            out[magic.size] = modeEncrypted
            System.arraycopy(data, 0, out, magic.size + 1, data.size)
            return out
        }

        private fun readEnvelope(data: ByteArray): Pair<Byte, ByteArray>? {
            if(data.size <= magic.size) return null
            if(!data.copyOfRange(0, magic.size).contentEquals(magic)) return null
            val mode = data[magic.size]
            val payload = data.copyOfRange(magic.size + 1, data.size)
            return mode to payload
        }

        private fun decryptPayload(payload: ByteArray, key: ByteArray, logFailure: Boolean = true): ByteArray? {
            if (payload.size <= gcmIv) {
                logger.warn("Encrypted payload too small to decrypt")
                return null
            }
            return try {
                decrypt(payload, key)
            } catch (t: Throwable) {
                if (logFailure) {
                    logger.warn("Failed to decrypt MSAL cache", t)
                }
                null
            }
        }

        private fun decryptWithAnyKey(payload: ByteArray, keys: List<ByteArray>): Pair<ByteArray, ByteArray>? {
            for (key in keys) {
                val plain = decryptPayload(payload, key, logFailure = false) ?: continue
                return plain to key
            }
            return null
        }

        private fun deserializeUtf8(ctx: ITokenCacheAccessContext, data: ByteArray) {
            ctx.tokenCache().deserialize(String(data, Charsets.UTF_8))
        }

        private fun writeCacheAtomically(bytes: ByteArray) {
            atomicWrite(cacheFile, bytes, durable = true)
        }

        override fun beforeCacheAccess(ctx: ITokenCacheAccessContext) {
            lock.lock()
            try {
                if (!cacheFile.exists()) return
                val enc = cacheFile.bytesOrNull() ?: return
                val env = readEnvelope(enc)
                if (env != null) {
                    val (mode, payload) = env
                    when (mode) {
                        modePlain -> deserializeUtf8(ctx, payload)
                        modeEncrypted -> {
                            val keys = candidateKeys()
                            if (keys.isEmpty()) {
                                logger.warn("MSAL cache is encrypted but encryption key is unavailable; skipping cache load")
                                return
                            }
                            val resolved = decryptWithAnyKey(payload, keys)
                            if (resolved == null) {
                                logger.warn("MSAL cache encrypted payload could not be decrypted with available keys; leaving cache untouched")
                                return
                            }
                            val (plain, keyUsed) = resolved
                            preferredKey = keyUsed.copyOf()
                            deserializeUtf8(ctx, plain)
                        }

                        else -> {
                            logger.warn("MSAL cache envelope mode '$mode' unrecognized; leaving cache file untouched")
                        }
                    }
                    return
                }

                val keys = candidateKeys()
                val decrypted = decryptWithAnyKey(enc, keys)
                if (decrypted != null) {
                    val (plain, keyUsed) = decrypted
                    preferredKey = keyUsed.copyOf()
                    deserializeUtf8(ctx, plain)
                    return
                }

                try {
                    deserializeUtf8(ctx, enc)
                    logger.warn("MSAL cache was unencrypted; using plaintext fallback")
                } catch (t: Throwable) {
                    logger.warn("MSAL cache unreadable with available keys; leaving cache file untouched", t)
                }
            } catch (t: Throwable) {
                logger.warn("'beforeCacheAccess' failed", t)
            } finally {
                lock.unlock()
            }
        }

        override fun afterCacheAccess(ctx: ITokenCacheAccessContext) {
            if(!ctx.hasCacheChanged()) return
            lock.lock()
            try {
                val data = ctx.tokenCache().serialize() ?: return
                val key = getOrCreateKey()
                if(key == null) {
                    writeCacheAtomically(wrapPlain(data.toByteArray(Charsets.UTF_8)))
                } else {
                    val enc = encrypt(data.toByteArray(Charsets.UTF_8), key)
                    writeCacheAtomically(wrapEncrypted(enc))
                }
            } catch (t: Throwable) {
                logger.warn("'afterCacheAccess' failed", t)
            } finally {
                lock.unlock()
            }
        }
    }
}
