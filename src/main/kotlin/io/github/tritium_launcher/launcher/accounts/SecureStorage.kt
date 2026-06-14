package io.github.tritium_launcher.launcher.accounts

import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.atomicWrite
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

//TODO: WINDOWS
internal object SecureStorage {
    private val dir = fromTR(".tokens").also { it.mkdirs() }
    private const val TOOL_TIMEOUT_SECONDS = 5L
    private val logger = logger()
    private const val AESKEYSIZE = 32
    private const val GCMIV = 12
    private const val TAGBITS = 128

    fun store(service: String, account: String, value: String): Boolean {
        when (Platform.current) {
            Platform.Linux -> {
                storeSecretTool(service, account, value)
            }
            else -> {}
        }
        return storeEncrypted(service, account, value)
    }

    fun retrieve(service: String, account: String): String? {
        when (Platform.current) {
            Platform.Linux -> {
                val fromTool = retrieveSecretTool(service, account)
                if (fromTool != null) return fromTool
            }
            else -> {}
        }
        return retrieveEncrypted(service, account)
    }

    fun delete(service: String, account: String) {
        when (Platform.current) {
            Platform.Linux -> {
                if (commandExists("secret-tool")) {
                    runSecretTool(
                        args = listOf("secret-tool", "clear", "service", service, "account", account)
                    )
                }
            }
            else -> {}
        }
        val file = dir.resolve(service).resolve("$account.enc")
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    fun listAccounts(service: String): List<String> {
        val result = mutableListOf<String>()
        val serviceDir = dir.resolve(service)
        if (serviceDir.exists() && serviceDir.isDir()) {
            serviceDir.listFiles { it.fileName().endsWith(".enc") }.forEach { f ->
                result.add(f.fileName().removeSuffix(".enc"))
            }
        }
        return result
    }

    private fun storeSecretTool(service: String, account: String, value: String): Boolean {
        if (!commandExists("secret-tool")) return false
        val result = runSecretTool(
            args = listOf("secret-tool", "store", "--label", "Tritium token: $service/$account", "service", service, "account", account),
            stdin = value
        )
        return result?.exitCode == 0
    }

    private fun retrieveSecretTool(service: String, account: String): String? {
        if (!commandExists("secret-tool")) return null
        val result = runSecretTool(
            args = listOf("secret-tool", "lookup", "service", service, "account", account)
        )
        if (result != null && result.exitCode == 0 && result.output.isNotBlank()) {
            return result.output
        }
        return null
    }

    private fun storeEncrypted(service: String, account: String, value: String): Boolean {
        return try {
            val key = getOrCreateKey()
            val iv = ByteArray(GCMIV)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(TAGBITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
            val serviceDir = dir.resolve(service)
            serviceDir.mkdirs()
            atomicWrite(serviceDir.resolve("$account.enc"), out, durable = true)
            true
        } catch (t: Throwable) {
            logger.warn("Failed to encrypt token for {}/{}", service, account, t)
            false
        }
    }

    private fun retrieveEncrypted(service: String, account: String): String? {
        return try {
            val file = dir.resolve(service).resolve("$account.enc")
            if (!file.exists()) return null
            val data = file.bytesOrNull() ?: return null
            if (data.size <= GCMIV) return null
            val iv = data.copyOfRange(0, GCMIV)
            val ct = data.copyOfRange(GCMIV, data.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(TAGBITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            logger.warn("Failed to decrypt token for {}/{}", service, account, t)
            null
        }
    }

    private val fallbackKeyFile = fromTR(".tokens").resolve("key.bin")

    private fun getOrCreateKey(): ByteArray {
        val existing = loadExistingKey()
        if (existing != null) return existing
        val key = ByteArray(AESKEYSIZE)
        SecureRandom().nextBytes(key)
        try {
            atomicWrite(fallbackKeyFile, key, durable = true)
            fallbackKeyFile.toJFile().setReadable(false, false)
            fallbackKeyFile.toJFile().setWritable(false, false)
            fallbackKeyFile.toJFile().setExecutable(false, false)
            fallbackKeyFile.toJFile().setReadable(true, true)
            fallbackKeyFile.toJFile().setWritable(true, true)
        } catch (t: Throwable) {
            logger.warn("Failed to persist secure storage key", t)
        }
        return key
    }

    private fun loadExistingKey(): ByteArray? {
        return try {
            if (!fallbackKeyFile.exists()) return null
            val key = fallbackKeyFile.bytesOrNull() ?: return null
            if (key.size != AESKEYSIZE) {
                logger.warn("Secure storage key has unexpected length, ignoring")
                return null
            }
            key
        } catch (t: Throwable) {
            logger.warn("Failed to load secure storage key", t)
            null
        }
    }

    private fun commandExists(command: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        for (dir in path.split(':')) {
            if (dir.isBlank()) continue
            val c = Path.of(dir, command)
            if (Files.isRegularFile(c) && Files.isExecutable(c)) return true
        }
        return false
    }

    private data class SecretToolResult(val exitCode: Int, val output: String)

    private fun runSecretTool(args: List<String>, stdin: String? = null): SecretToolResult? {
        val action = if (args.size >= 2) args[1] else "command"
        if (!commandExists("secret-tool")) return null
        return try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
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
}
