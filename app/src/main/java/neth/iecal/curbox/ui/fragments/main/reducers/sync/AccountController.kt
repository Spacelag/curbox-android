package neth.iecal.curbox.ui.fragments.main.reducers.sync

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.sync.SyncGateway
import neth.iecal.curbox.data.sync.SyncStatus

/**
 * Drives the whole account experience over a view_account layout: sign in, sign
 * up, the emailed code, password reset, the secret phrase, and pairing. Shared
 * by the standalone Sync screen and the onboarding step so both look and behave
 * the same.
 */
class AccountController(
    private val root: View,
    private val fragment: Fragment,
    private val requestScan: () -> Unit,
) {
    private enum class Mode { SIGN_IN, SIGN_UP, FORGOT, RESET }

    private val provider get() = SyncGateway.provider

    private var mode = Mode.SIGN_IN
    private var last = SyncStatus()

    private val title = root.findViewById<TextView>(R.id.text_title)
    private val subtitle = root.findViewById<TextView>(R.id.text_subtitle)
    private val message = root.findViewById<TextView>(R.id.text_message)

    private val email = root.findViewById<TextInputEditText>(R.id.input_email)
    private val password = root.findViewById<TextInputEditText>(R.id.input_password)
    private val code = root.findViewById<TextInputEditText>(R.id.input_code)
    private val forgotEmail = root.findViewById<TextInputEditText>(R.id.input_forgot_email)
    private val resetCode = root.findViewById<TextInputEditText>(R.id.input_reset_code)
    private val newPassword = root.findViewById<TextInputEditText>(R.id.input_new_password)
    private val passphrase = root.findViewById<TextInputEditText>(R.id.input_passphrase)
    private val pairInput = root.findViewById<TextInputEditText>(R.id.input_pair)

    private val sectionAuth = root.findViewById<View>(R.id.section_auth)
    private val sectionVerify = root.findViewById<View>(R.id.section_verify)
    private val sectionForgot = root.findViewById<View>(R.id.section_forgot)
    private val sectionReset = root.findViewById<View>(R.id.section_reset)
    private val sectionPassphrase = root.findViewById<View>(R.id.section_passphrase)
    private val pairingBlock = root.findViewById<View>(R.id.pairing_block)
    private val sectionUnlocked = root.findViewById<View>(R.id.section_unlocked)

    private val primaryAuth = root.findViewById<MaterialButton>(R.id.btn_primary_auth)
    private val togglePrompt = root.findViewById<TextView>(R.id.text_toggle_prompt)
    private val toggleMode = root.findViewById<TextView>(R.id.btn_toggle_mode)
    private val passphraseBtn = root.findViewById<MaterialButton>(R.id.btn_passphrase)
    private val qr = root.findViewById<ImageView>(R.id.image_qr)

    fun bind() {
        primaryAuth.setOnClickListener {
            if (mode == Mode.SIGN_UP) {
                submit { provider.signUp(text(email), text(password)) }
            } else {
                submit { provider.signIn(text(email), text(password)) }
            }
        }
        toggleMode.setOnClickListener {
            mode = if (mode == Mode.SIGN_IN) Mode.SIGN_UP else Mode.SIGN_IN
            apply()
        }
        root.findViewById<View>(R.id.btn_forgot).setOnClickListener { mode = Mode.FORGOT; apply() }
        root.findViewById<View>(R.id.btn_forgot_back).setOnClickListener { mode = Mode.SIGN_IN; apply() }

        root.findViewById<View>(R.id.btn_verify).setOnClickListener {
            val e = last.pendingEmail ?: return@setOnClickListener
            submit { provider.verifySignupCode(e, text(code)) }
        }
        root.findViewById<View>(R.id.btn_resend).setOnClickListener {
            val e = last.pendingEmail ?: return@setOnClickListener
            submit("I sent a new code to $e") { provider.resendSignupCode(e) }
        }
        root.findViewById<View>(R.id.btn_send_reset).setOnClickListener {
            val e = text(forgotEmail)
            submit("I sent a reset code to $e") { provider.sendPasswordReset(e); resetEmail = e; mode = Mode.RESET; apply() }
        }
        root.findViewById<View>(R.id.btn_set_password).setOnClickListener {
            submit { provider.resetPassword(resetEmail, text(resetCode), text(newPassword)) }
        }

        passphraseBtn.setOnClickListener {
            val phrase = text(passphrase)
            if (!last.hasVault && phrase.length < 8) {
                Toast.makeText(fragment.requireContext(), "Please pick a secret phrase with at least 8 letters.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            submit { if (last.hasVault) provider.unlock(phrase) else provider.setPassphrase(phrase) }
        }
        root.findViewById<View>(R.id.btn_pair).setOnClickListener { submit { provider.pairWithCode(text(pairInput)) } }
        root.findViewById<View>(R.id.btn_scan).setOnClickListener { requestScan() }
        root.findViewById<View>(R.id.btn_show_code).setOnClickListener {
            submit {
                val payload = provider.makePairingCode()
                val bmp = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 600, 600)
                qr.setImageBitmap(bmp)
                qr.visibility = View.VISIBLE
            }
        }
        root.findViewById<View>(R.id.btn_force_sync).setOnClickListener {
            submit("Syncing your latest data now") { provider.pushNow(); provider.refresh() }
        }
        root.findViewById<View>(R.id.btn_signout).setOnClickListener { submit { provider.signOut() } }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                provider.status.collect { last = it; apply() }
            }
        }
        apply()
    }

    fun pairWith(payload: String) = submit { provider.pairWithCode(payload) }

    private var resetEmail = ""

    private fun apply() {
        val signedIn = last.signedIn
        val unlocked = last.unlocked
        val verifying = !signedIn && last.pendingEmail != null

        sectionAuth.visibility = vis(!signedIn && !verifying && (mode == Mode.SIGN_IN || mode == Mode.SIGN_UP))
        sectionForgot.visibility = vis(!signedIn && !verifying && mode == Mode.FORGOT)
        sectionReset.visibility = vis(!signedIn && !verifying && mode == Mode.RESET)
        sectionVerify.visibility = vis(verifying)
        sectionPassphrase.visibility = vis(signedIn && !unlocked)
        // Pairing only makes sense once a passphrase already exists on another
        // device. On the very first device there is nothing to pair with, so it
        // would only confuse. Hide it until a vault exists.
        pairingBlock.visibility = vis(signedIn && !unlocked && last.hasVault)
        sectionUnlocked.visibility = vis(unlocked)
        if (!unlocked) qr.visibility = View.GONE

        if (mode == Mode.SIGN_UP) {
            primaryAuth.text = "Create account"
            togglePrompt.text = "Already have an account? "
            toggleMode.text = "Sign in"
        } else {
            primaryAuth.text = "Sign in"
            togglePrompt.text = "New here? "
            toggleMode.text = "Create an account"
        }

        if (signedIn && !unlocked) {
            passphraseBtn.text = if (last.hasVault) "Unlock my data" else "Turn on sync"
        }

        title.text = when {
            unlocked -> "Sync is on"
            signedIn -> if (last.hasVault) "Unlock my data" else "Make a secret phrase"
            verifying -> "Check your email"
            mode == Mode.FORGOT -> "Reset password"
            mode == Mode.RESET -> "Choose a new password"
            mode == Mode.SIGN_UP -> "Create your account"
            else -> "Welcome back"
        }

        subtitle.text = when {
            unlocked -> "Your settings and time stats stay in step on every device."
            signedIn && last.hasVault -> "Type the secret phrase you made on your first device. Or if that device is nearby, tap Scan a code below to bring this one in without typing anything."
            signedIn -> "This one phrase unlocks your data on every device. I never send it anywhere, so pick something you will remember and keep it safe. If you forget it, your data cannot be opened again."
            verifying -> "I sent a code to ${last.pendingEmail}. Type it below to finish setting up."
            mode == Mode.FORGOT -> "Tell me your email and I will send you a code to set a new password."
            mode == Mode.RESET -> "Type the code I emailed you and your new password."
            mode == Mode.SIGN_UP -> "Make an account so your settings and stats can travel with you."
            else -> "Sign in to keep your settings and time stats in step on every device, end to end encrypted."
        }

        val msg = last.error
        message.visibility = if (msg != null) View.VISIBLE else View.GONE
        if (msg != null) message.text = msg
    }

    private fun vis(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun text(field: TextInputEditText): String = field.text?.toString()?.trim().orEmpty()

    private fun submit(toast: String? = null, block: suspend () -> Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
                if (toast != null) Toast.makeText(fragment.requireContext(), toast, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(fragment.requireContext(), friendly(e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Turns raw server and network errors into calm, plain language. Keeps the
    // reader unworried and tells them what to do next.
    private fun friendly(raw: String?): String {
        val m = raw?.lowercase() ?: return "Something went wrong. Please try again."
        return when {
            "invalid login" in m || ("invalid" in m && "credential" in m) ->
                "That email or password did not match. Please try again."
            "already registered" in m || "already been registered" in m ->
                "You already have an account with this email. Please sign in instead."
            "email not confirmed" in m -> "Please confirm your email first. I can send you a new code."
            "token has expired" in m || "otp" in m || ("invalid" in m && "code" in m) ->
                "That code did not work. Check it or ask me for a new one."
            "for security purposes" in m || "rate limit" in m || "too many" in m ->
                "Please wait a moment, then try again."
            "password should be" in m || "at least" in m && "character" in m ->
                "Please use a password with at least 6 letters or numbers."
            "unable to resolve host" in m || "failed to connect" in m || "timeout" in m ||
                "network" in m || "no address associated" in m ->
                "I cannot reach the internet right now. Check your connection and try again."
            else -> raw ?: "Something went wrong. Please try again."
        }
    }
}
