package com.rameen.i230806.ui.auth

import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.rameen.i230806.R
import com.rameen.i230806.data.AuthRepo
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityLoginBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.RecentLogin

/**
 * 02 Login with our PHP web service (Assignment 3).
 *
 *  - Log in: auth/login.php checks the email and password (password_verify against the stored
 *    hash) and answers with a session token and the profile. AuthRepo saves both, then we go to
 *    Home (or Edit profile the first time). Errors show the server's message.
 *  - Recent login card: the last account used on this phone (RecentLogin). Tapping it fills
 *    in the email so only the password is left; "..." removes it from this phone.
 *  - Forgot password: our local server can't send emails, so it only shows a message.
 *  - Create new account opens Sign up (Back returns here).
 *  - The eye shows or hides the password.
 */
class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The layout still holds Assignment 1's sample values so the design preview looks
        // right; real users start with empty fields.
        binding.etEmail.setText("")
        binding.etPassword.setText("")

        showRecentLogin()

        binding.btnLogin.setOnClickListener { logIn() }
        binding.tvForgotPassword.setOnClickListener { sendPasswordReset() }
        binding.btnCreateAccount.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
        binding.ivPasswordEye.setOnClickListener { togglePassword() }
    }

    // ---------------------------------------------------------------------------------------
    // Recent login card
    // ---------------------------------------------------------------------------------------

    /** Shows the card for the last account on this phone, or hides the whole section. */
    private fun showRecentLogin() {
        val recent = RecentLogin.load(this)
        val visibility = if (recent == null) View.GONE else View.VISIBLE
        binding.tvRecentLogin.visibility = visibility
        binding.llRecentLogin.visibility = visibility
        if (recent == null) return

        binding.tvRecentName.text = recent.name
        binding.tvRecentBadge.visibility = View.GONE   // the sample "2" badge from the design
        // Coloured initials (the profile picture can only be read after logging in).
        Avatars.bind(binding.tvRecentAvatar, null, User(uid = recent.uid, name = recent.name))

        // Tap: fill in the email and put the cursor in the password field.
        binding.llRecentLogin.setOnClickListener {
            binding.etEmail.setText(recent.email)
            binding.etPassword.requestFocus()
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(binding.etPassword, InputMethodManager.SHOW_IMPLICIT)
        }
        // "...": forget this account on this phone.
        binding.ivRecentMore.contentDescription = getString(R.string.cd_forget_recent)
        binding.ivRecentMore.setOnClickListener {
            RecentLogin.clear(this)
            toast(R.string.login_forget_recent)
            showRecentLogin()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Log in
    // ---------------------------------------------------------------------------------------

    private fun logIn() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            toast(R.string.login_error_empty)
            return
        }

        setBusy(true)
        AuthRepo.logIn(email, password) { user, error ->
            setBusy(false)
            if (user == null) {
                // "Wrong email or password", or "No connection to the server" when offline.
                toast(error ?: getString(R.string.error_generic))
            } else {
                RecentLogin.save(this, user)
                openAfterLogin(user)
            }
        }
    }

    /** Password reset needs an email server, which the local XAMPP setup doesn't have. */
    private fun sendPasswordReset() {
        val email = binding.etEmail.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast(R.string.login_reset_need_email)
            return
        }
        toast(R.string.login_reset_unavailable)
    }

    /** While the server is working, the button is disabled so it can't be pressed twice. */
    private fun setBusy(busy: Boolean) {
        binding.btnLogin.isEnabled = !busy
        binding.btnLogin.alpha = if (busy) 0.6f else 1f
    }

    /** Swaps the password between dots and plain text, keeping the cursor at the end. */
    private fun togglePassword() {
        val field = binding.etPassword
        val hidden = field.transformationMethod is PasswordTransformationMethod
        field.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
        field.setSelection(field.text.length)
        binding.ivPasswordEye.contentDescription =
            getString(if (hidden) R.string.cd_hide_password else R.string.cd_show_password)
    }
}
