package com.mitas.ppnam.station5aa.cage

object StatusText {
    fun grv(state: String): String = when (state) {
        "Not queued" -> "Not sent yet"
        "Pending" -> "Pending"
        "Processing" -> "Sending to SAP"
        "Succeeded" -> "Posted to SAP"
        "Failed" -> "Failed — check the desktop"
        "OutcomeUnknown" -> "Unconfirmed — check the desktop"
        "Reconcile" -> "Needs review on the desktop"
        else -> state
    }

    fun print(state: String): String = when (state) {
        "WaitingForGrv" -> "Waiting for receipt"
        "Succeeded" -> "Printed"
        "NeedsReview" -> "Needs review on the desktop"
        "Failed" -> "Failed — check the desktop"
        "Pending" -> "Pending"
        else -> state
    }
}
