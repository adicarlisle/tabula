package app.tabula

import app.tabula.core.HostKeyDecision
import app.tabula.core.KnownHosts
import app.tabula.core.ServerAddress
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream

/** Thrown when the server's key isn't known yet: show [fingerprint] and let the user decide. */
class UnknownHostKey(val fingerprint: String) : Exception("Unknown host key $fingerprint")

class ChangedHostKey(val fingerprint: String) : Exception("Server key changed: $fingerprint. Refusing to connect.")

/** SSH to the user's own server. Host keys are checked against [known] (trust on first use). */
class Ssh(private val known: KnownHosts) {
    private val jsch = JSch()

    private inner class Repo(private val addr: ServerAddress) : HostKeyRepository {
        var seen: String? = null
        override fun check(host: String, key: ByteArray): Int {
            val fp = HostKey(host, key).getFingerPrint(jsch)
            seen = fp
            return when (known.check(hostId(addr), fp)) {
                HostKeyDecision.TRUSTED -> HostKeyRepository.OK
                HostKeyDecision.ASK_USER -> HostKeyRepository.NOT_INCLUDED
                HostKeyDecision.CHANGED_REFUSE -> HostKeyRepository.CHANGED
            }
        }
        override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
        override fun remove(host: String, type: String?) = Unit
        override fun remove(host: String, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "tabula"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
    }

    /** Connects with a password (first setup only) or with the phone's own key. */
    fun connect(addr: ServerAddress, password: CharArray? = null, privateKey: ByteArray? = null): Session {
        val repo = Repo(addr)
        jsch.hostKeyRepository = repo
        jsch.removeAllIdentity()
        if (privateKey != null) jsch.addIdentity("tabula", privateKey, null, null)
        val session = jsch.getSession(addr.user, addr.host, addr.port)
        if (password != null) session.setPassword(String(password))
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (privateKey != null) "publickey" else "password,keyboard-interactive")
        try {
            session.connect(15_000)
        } catch (e: Exception) {
            val fp = repo.seen
            if (fp != null) when (known.check(hostId(addr), fp)) {
                HostKeyDecision.ASK_USER -> throw UnknownHostKey(fp)
                HostKeyDecision.CHANGED_REFUSE -> throw ChangedHostKey(fp)
                HostKeyDecision.TRUSTED -> Unit
            }
            throw e
        }
        return session
    }

    /** Runs [command]; [stdin] is written then closed. Output lines go to [onLine]. Returns the exit code. */
    fun run(session: Session, command: String, stdin: ByteArray? = null, onLine: (String) -> Unit = {}): Int {
        val ch = session.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val err = ByteArrayOutputStream()
        ch.setErrStream(err)
        val out = ch.inputStream
        val input = ch.outputStream
        ch.connect(15_000)
        if (stdin != null) input.write(stdin)
        input.close()
        out.bufferedReader().forEachLine(onLine)
        while (!ch.isClosed) Thread.sleep(50)
        err.toString().lines().filter { it.isNotBlank() }.forEach(onLine)
        return ch.exitStatus.also { ch.disconnect() }
    }

    /** A new ECDSA key pair for this phone: private key bytes and the authorized_keys line. */
    fun newKeyPair(): Pair<ByteArray, String> {
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        val prv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "tabula-phone") }.toString().trim()
        kp.dispose()
        return prv to pub
    }

    companion object {
        fun hostId(addr: ServerAddress) = "${addr.host}:${addr.port}"

        /** Runs a short command on an open session and appends its output to [into]. */
        fun readText(session: Session, command: String, into: StringBuilder) {
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            val out = ch.inputStream
            ch.connect(15_000)
            into.append(out.bufferedReader().readText())
            ch.disconnect()
        }
    }
}
