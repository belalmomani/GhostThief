package com.ghostthief.game.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.ghostthief.game.BuildConfig
import com.ghostthief.game.logic.Progress

/**
 * Firebase Anonymous Auth + Firestore (users/{uid}).
 * لا يعمل إلا إذا وضعتَ google-services.json الخاص بمشروعك. بدونه تبقى اللعبة محلية بالكامل.
 */
class FirebaseBackend(private val context: Context) : CloudBackend {

    override val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_CONFIGURED && try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (t: Throwable) { false }

    private fun withUid(cb: (Result<String>) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user != null) { cb(Result.success(user.uid)); return }
        auth.signInAnonymously()
            .addOnSuccessListener { r ->
                val uid = r.user?.uid
                if (uid != null) cb(Result.success(uid)) else cb(Result.failure(IllegalStateException("no uid")))
            }
            .addOnFailureListener { cb(Result.failure(it)) }
    }

    override fun fetch(callback: (Result<Progress?>) -> Unit) {
        withUid { uidResult ->
            uidResult.onFailure { callback(Result.failure(it)) }
            uidResult.onSuccess { uid ->
                FirebaseFirestore.getInstance().collection("users").document(uid).get()
                    .addOnSuccessListener { doc ->
                        if (!doc.exists()) { callback(Result.success(null)); return@addOnSuccessListener }
                        val ach = (doc.get("achievements") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                        callback(Result.success(Progress(
                            captures = (doc.getLong("captures") ?: 0L).toInt(),
                            circleCaptures = (doc.getLong("circleCaptures") ?: 0L).toInt(),
                            points = (doc.getLong("points") ?: 0L).toInt(),
                            achievements = ach,
                            resetEpoch = (doc.getLong("resetEpoch") ?: 0L).toInt()
                        )))
                    }
                    .addOnFailureListener { callback(Result.failure(it)) }
            }
        }
    }

    override fun push(progress: Progress, callback: (Result<Unit>) -> Unit) {
        withUid { uidResult ->
            uidResult.onFailure { callback(Result.failure(it)) }
            uidResult.onSuccess { uid ->
                val data = hashMapOf<String, Any>(
                    "captures" to progress.captures,
                    "circleCaptures" to progress.circleCaptures,
                    "points" to progress.points,
                    "achievements" to progress.achievements.toList(),
                    "resetEpoch" to progress.resetEpoch,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                FirebaseFirestore.getInstance().collection("users").document(uid).set(data)
                    .addOnSuccessListener { callback(Result.success(Unit)) }
                    .addOnFailureListener { callback(Result.failure(it)) }
            }
        }
    }
}
