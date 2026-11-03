package com.mitas.ppnam.station5aa.cage

import android.content.Context

class SharedPrefsOutboxStore(context: Context) : OutboxStore {
    private val prefs = context.applicationContext.getSharedPreferences("cage_outbox", Context.MODE_PRIVATE)

    override fun load(): PendingRequest? = PendingRequestCodec.decode(prefs.getString(KEY, null))

    override fun save(p: PendingRequest) {
        prefs.edit().putString(KEY, PendingRequestCodec.encode(p)).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object { const val KEY = "pending" }
}
