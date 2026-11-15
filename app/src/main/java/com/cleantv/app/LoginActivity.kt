package com.cleantv.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        if (prefs.isLoggedIn && !intent.getBooleanExtra(EXTRA_RELOGIN, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)
        val server = findViewById<EditText>(R.id.server)
        val user = findViewById<EditText>(R.id.username)
        val pass = findViewById<EditText>(R.id.password)
        val button = findViewById<Button>(R.id.signIn)
        val status = findViewById<TextView>(R.id.status)

        server.setText(prefs.server)
        user.setText(prefs.username)

        button.setOnClickListener {
            val s = server.text.toString().trim()
            val u = user.text.toString().trim()
            val p = pass.text.toString().trim()
            if (s.isEmpty() || u.isEmpty() || p.isEmpty()) {
                status.text = "Fill in all three boxes"
                return@setOnClickListener
            }
            button.isEnabled = false
            status.text = "Signing in…"
            thread {
                val result = runCatching { XtreamApi(s, u, p).login() }
                runOnUiThread {
                    button.isEnabled = true
                    result.onSuccess {
                        prefs.server = XtreamApi.normalizeServer(s)
                        prefs.username = u
                        prefs.password = p
                        Library.clear()
                        Shows.clear()
                        Catalog.clear()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }.onFailure {
                        status.text = XtreamApi.friendlyError(it)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_RELOGIN = "relogin"
    }
}
