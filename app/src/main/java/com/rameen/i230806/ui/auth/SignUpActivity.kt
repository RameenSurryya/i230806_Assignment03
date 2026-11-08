package com.rameen.i230806.ui.auth

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivitySignUpBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.RecentLogin
import java.util.Calendar
import java.util.Locale

/**
 * 03 Sign up with Firebase Authentication.
 *
 *  1. The form is checked first (names, a real birthday, age 13+, gender, email, a Medium or
 *     Strong password, terms ticked). Problems are shown in a short message.
 *  2. Firebase creates the account (email + password) and gives it a uid.
 *  3. The profile is saved to users/{uid} in the Realtime Database.
 *  4. The user goes on to Edit profile (step 3 of 3) to choose a username, bio and photo.
 *
 * The password strength bar updates on every key press (a TextWatcher).
 * Back arrow and "Log in" close this screen, which returns to Login underneath.
 */
class SignUpActivity : BaseActivity() {

    private lateinit var binding: ActivitySignUpBinding

    /** Years shown in the Year dropdown: this year down to 100 years ago. */
    private val years: List<Int> by lazy {
        val now = Calendar.getInstance().get(Calendar.YEAR)
        (now downTo now - 100).toList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton(R.id.iv_back)
        binding.tvLogIn.setOnClickListener { finish() }
        binding.ivPasswordEye.setOnClickListener { togglePassword() }
        binding.btnCreateAccount.setOnClickListener { createAccount() }

        setupBirthdayDropdowns()
        setupGenderBold()

        // Live password strength: runs after every change to the password field.
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = showStrength(strengthOf(s.toString()))
        })
    }

    // ---------------------------------------------------------------------------------------
    // Birthday and gender
    // ---------------------------------------------------------------------------------------

    /** Fills Day (1-31), Month (Jan-Dec) and Year, starting at 1 Jan of 20 years ago. */
    private fun setupBirthdayDropdowns() {
        fill(binding.spDay, (1..31).map { it.toString() })
        fill(binding.spMonth, resources.getStringArray(R.array.months_short).toList())
        fill(binding.spYear, years.map { it.toString() })
        binding.spYear.setSelection(20)
    }

    /** Puts [items] in a dropdown, styled like the other fields (the Spinner's theme overlay). */
    private fun fill(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(spinner.context, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    /** The chosen gender's text is bold, as in the design; the others are regular. */
    private fun setupGenderBold() {
        val bold = ResourcesCompat.getFont(this, R.font.figtree_bold)
        val regular = ResourcesCompat.getFont(this, R.font.figtree_regular)
        binding.rgGender.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                val option = group.getChildAt(i) as RadioButton
                option.typeface = if (option.id == checkedId) bold else regular
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Password strength
    // ---------------------------------------------------------------------------------------

    /**
     * 0 = empty, 1 = Weak, 2 = Medium, 3 = Strong.
     * Counts the kinds of characters used (lowercase, uppercase, digits, symbols):
     *   "abc"          -> Weak   (shorter than 8)
     *   "jacob2004"    -> Medium (8+ characters, 2 kinds)
     *   "Jacob2004!"   -> Strong (10+ characters, 3+ kinds)
     */
    private fun strengthOf(password: String): Int {
        if (password.isEmpty()) return 0
        val kinds = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        return when {
            password.length < 8 || kinds < 2 -> 1
            password.length >= 10 && kinds >= 3 -> 3
            else -> 2
        }
    }

    /** Colours 1, 2 or 3 segments and sets the label. */
    private fun showStrength(level: Int) {
        val colorRes = when (level) {
            1 -> R.color.love_red
            2 -> R.color.accent_orange
            else -> R.color.online_green
        }
        val color = ContextCompat.getColor(this, colorRes)
        val track = ContextCompat.getColor(this, R.color.track)
        val segments = listOf(binding.vStrength1, binding.vStrength2, binding.vStrength3)
        segments.forEachIndexed { index, segment ->
            segment.backgroundTintList =
                ColorStateList.valueOf(if (index < level) color else track)
        }

        binding.tvStrength.visibility = if (level == 0) View.INVISIBLE else View.VISIBLE
        binding.tvStrength.setTextColor(color)
        binding.tvStrength.setText(
            when (level) {
                1 -> R.string.signup_strength_weak
                2 -> R.string.signup_strength_medium
                else -> R.string.signup_strength_strong
            }
        )
    }

    // ---------------------------------------------------------------------------------------
    // Create the account
    // ---------------------------------------------------------------------------------------

    private fun createAccount() {
        val first = binding.etFirstName.text.toString().trim()
        val last = binding.etLastName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val genderId = binding.rgGender.checkedRadioButtonId   // View.NO_ID if none chosen
        val genderButton: RadioButton? = if (genderId == View.NO_ID) null else findViewById(genderId)

        // 1. Check the form, stopping at the first problem.
        val birthday = birthdayOrNull()
        val problem = when {
            first.isEmpty() || last.isEmpty() -> R.string.signup_error_name
            birthday == null -> R.string.signup_error_birthday
            ageOn(birthday!!) < 13 -> R.string.signup_error_age
            genderButton == null -> R.string.signup_error_gender
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.signup_error_email
            strengthOf(password) < 2 -> R.string.signup_error_password
            !binding.cbTerms.isChecked -> R.string.signup_error_terms
            else -> null
        }
        if (problem != null) {
            toast(problem)
            return
        }

        setBusy(true)
        // 2. Create the Firebase account.
        Db.auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val name = "$first $last"
                val user = User(
                    uid = result.user!!.uid,
                    name = name,
                    nameLower = name.lowercase(Locale.getDefault()),
                    email = email,
                    gender = genderButton!!.text.toString(),
                    birthday = format(birthday!!),
                    createdAt = Db.now(),
                    online = true,
                    setupDone = false,          // Edit profile finishes the setup
                )
                // 3. Save the profile to users/{uid}.
                Db.user(user.uid).setValue(user)
                    .addOnSuccessListener {
                        Db.remember(user)
                        RecentLogin.save(this, user)
                        // 4. Edit profile (first time), with the login screens removed.
                        openAfterLogin(user)
                    }
                    .addOnFailureListener {
                        setBusy(false)
                        toast(R.string.error_generic)
                    }
            }
            .addOnFailureListener { error ->
                setBusy(false)
                toast(
                    when (error) {
                        is FirebaseAuthUserCollisionException -> R.string.signup_error_exists
                        is FirebaseAuthWeakPasswordException -> R.string.signup_error_password
                        is FirebaseAuthInvalidCredentialsException -> R.string.signup_error_email
                        is FirebaseNetworkException -> R.string.error_network
                        else -> R.string.error_generic
                    }
                )
            }
    }

    /** The chosen date, or null if it doesn't exist (e.g. 31 Feb). */
    private fun birthdayOrNull(): Calendar? {
        val date = Calendar.getInstance().apply {
            isLenient = false   // don't silently turn 31 Feb into 3 Mar
            clear()
            set(
                years[binding.spYear.selectedItemPosition],
                binding.spMonth.selectedItemPosition,   // 0 = January
                binding.spDay.selectedItemPosition + 1,
            )
        }
        return try {
            date.timeInMillis   // throws for an impossible date
            date
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Full years between [birthday] and today. */
    private fun ageOn(birthday: Calendar): Int {
        val today = Calendar.getInstance()
        var age = today.get(Calendar.YEAR) - birthday.get(Calendar.YEAR)
        if (today.get(Calendar.DAY_OF_YEAR) < birthday.get(Calendar.DAY_OF_YEAR)) age--
        return age
    }

    /** "14 Mar 2004" */
    private fun format(birthday: Calendar): String {
        val month = resources.getStringArray(R.array.months_short)[birthday.get(Calendar.MONTH)]
        return "${birthday.get(Calendar.DAY_OF_MONTH)} $month ${birthday.get(Calendar.YEAR)}"
    }

    /** While Firebase is working: button disabled and showing "Creating your account…". */
    private fun setBusy(busy: Boolean) {
        binding.btnCreateAccount.isEnabled = !busy
        binding.btnCreateAccount.alpha = if (busy) 0.6f else 1f
        binding.btnCreateAccount.setText(if (busy) R.string.signup_creating else R.string.signup_button)
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