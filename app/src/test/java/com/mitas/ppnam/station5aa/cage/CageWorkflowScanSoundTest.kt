package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Suite rule S1 (UI_Design/sounds/README.md): each scan outcome gives exactly one signal, success
 * when the station accepts it, fail when it is refused (by the station or on the handheld) or its
 * request expires. The raw read, button taps, sign-in and navigation give none.
 */
class CageWorkflowScanSoundTest : CageWorkflowFixture() {

    @Before fun setUp() = signIn()

    private fun signals(effects: List<Effect>) =
        effects.filter { it == Effect.SuccessFeedback || it == Effect.ErrorFeedback }

    private val success = listOf<Effect>(Effect.SuccessFeedback)
    private val fail = listOf<Effect>(Effect.ErrorFeedback)

    private fun allocatedAnswer(bagId: String = "B9", packCount: Int = 3) =
        accepted("""{"accepted":true,"requiresCageScan":true,"nextAction":"select_cage",
            "load":${loadJson("CAGE-007", packCount, "75.5")},"pack":${allocatedPackJson(bagId)}}""")

    // ---- cage tag (RFID) -----------------------------------------------------------------------

    @Test fun rawCageTagReadIsSilent() {
        toLoadingUnarmed()
        assertEquals(emptyList<Effect>(), signals(wf.onRfid("TAG-CAGE-007")))
    }

    @Test fun cageArmedGivesOneSuccess() {
        toLoadingUnarmed()
        wf.onRfid("TAG-CAGE-007")
        assertEquals(success, signals(wf.onResponse(selectArmed("CAGE-007", 2, "50.4"))))
    }

    @Test fun cageSelectedWithoutArmGivesOneSuccess() {
        wf.onRfid("TAG-CAGE-007")
        val effects = wf.onResponse(accepted("""{"accepted":true,"requiresCageScan":true,
            "load":${loadJson("CAGE-007", 2, "50.4")}}"""))
        assertEquals(success, signals(effects))
    }

    @Test fun selectAcceptedWithoutOpenLoadGivesOneFail() {
        wf.onRfid("TAG-CAGE-007")
        val effects = wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", status = "Completed")}}"""))
        assertEquals(fail, signals(effects))
        assertEquals(CageScreen.Idle, wf.state.screen)
    }

    @Test fun cageRefusedGivesOneFail() {
        wf.onRfid("TAG-UNKNOWN")
        assertEquals(fail, signals(wf.onResponse(rejected("cage_not_found"))))
    }

    // ---- Pack QR -------------------------------------------------------------------------------

    @Test fun rawPackReadIsSilent() {
        toLoading()
        assertEquals(emptyList<Effect>(), signals(wf.onQr(validQr)))
    }

    @Test fun packAllocatedGivesOneSuccess() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(success, signals(wf.onResponse(allocatedAnswer())))
    }

    @Test fun packAlreadyInCageGivesOneSuccess() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(success, signals(wf.onResponse(allocatedAnswer().put("alreadyAllocated", true))))
        assertEquals(Notice.Tone.INFO, wf.state.notice?.tone) // the notice still says it is not new
    }

    @Test fun packRefusedGivesOneFail() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(fail, signals(wf.onResponse(rejected("pack_allocated"))))
    }

    @Test fun refreshAfterPackIsSilent() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer())
        val effects = wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 3, "75.5")}}"""))
        assertEquals(emptyList<Effect>(), signals(effects))
    }

    // ---- refused on the handheld ---------------------------------------------------------------

    @Test fun scanCageTagFirstGivesOneFail() {
        toLoadingUnarmed()
        assertEquals(fail, signals(wf.onQr(validQr)))
        assertEquals("local_cage_scan_required", wf.state.refusal?.code)
    }

    @Test fun packWithNoCageShownGivesOneFail() {
        assertEquals(fail, signals(wf.onQr(validQr)))
    }

    @Test fun unrecognisedLabelGivesOneFail() {
        toLoading()
        assertEquals(fail, signals(wf.onQr("OUTER,BAG")))
        assertEquals("local_invalid_qr", wf.state.refusal?.code)
    }

    @Test fun scanWhileSendingGivesOneFail() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(fail, signals(wf.onQr("510019399,O,B10")))
        assertEquals(fail, signals(wf.onRfid("TAG-OTHER")))
    }

    @Test fun scanWhileOfflineGivesOneFailAndChangesNothingShown() {
        toLoading()
        wf.onStationOnline(false)
        val before = wf.state
        assertEquals(fail, signals(wf.onQr(validQr)))
        assertEquals(fail, signals(wf.onRfid("TAG-OTHER")))
        assertEquals(before, wf.state)
        assertNull(outbox.load())
    }

    @Test fun scanWhileOfflineAndSendingGivesOneFail() {
        toLoading()
        wf.onQr(validQr)
        wf.onStationOnline(false)
        assertEquals(fail, signals(wf.onQr("510019399,O,B10")))
    }

    // ---- repeat reads (suite rule S3) ----------------------------------------------------------

    private val waitNotice = Notice("Wait for Sending… to finish, then scan again.", Notice.Tone.WARNING)

    @Test fun repeatReadOfPackInFlightIsSilentAndShowsNoWait() {
        toLoading()
        wf.onQr(validQr)
        val before = wf.state
        assertEquals(emptyList<Effect>(), wf.onQr(validQr))
        assertEquals(emptyList<Effect>(), wf.onQr(" $validQr "))
        assertEquals(before, wf.state)
        assertEquals(success, signals(wf.onResponse(allocatedAnswer()))) // the scan sounds once, on its answer
    }

    @Test fun repeatReadOfCageTagInFlightIsSilent() {
        toLoadingUnarmed()
        wf.onRfid("TAG-CAGE-007")
        assertEquals(emptyList<Effect>(), wf.onRfid("TAG-CAGE-007"))
        assertNull(wf.state.notice)
        assertEquals(success, signals(wf.onResponse(selectArmed("CAGE-007", 2, "50.4"))))
    }

    @Test fun repeatReadDuringRefreshAfterPackIsSilent() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer()) // now sending the refresh
        assertEquals(emptyList<Effect>(), wf.onQr(validQr))
    }

    @Test fun differentValueWhileSendingFailsOnceAndWarns() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(fail, signals(wf.onQr("510019399,O,B10")))
        assertEquals(waitNotice, wf.state.notice)
        clockMs += 200
        assertEquals(emptyList<Effect>(), signals(wf.onQr("510019399,O,B10"))) // its own repeat read
        assertEquals(waitNotice, wf.state.notice)
    }

    @Test fun repeatOfLocallyRefusedValueIsSilentFor1500ms() {
        toLoading()
        wf.onStationOnline(false)
        assertEquals(fail, signals(wf.onQr(validQr)))
        clockMs += 1_000
        assertEquals(emptyList<Effect>(), signals(wf.onQr(validQr)))
        assertEquals(fail, signals(wf.onQr("510019399,O,B10"))) // a different value is a new scan
        clockMs += 1_499
        assertEquals(emptyList<Effect>(), signals(wf.onQr("510019399,O,B10")))
        clockMs += 1
        assertEquals(fail, signals(wf.onQr("510019399,O,B10")))
    }

    @Test fun repeatOfUnrecognisedLabelAfterDismissIsSilent() {
        toLoading()
        assertEquals(fail, signals(wf.onQr("OUTER,BAG")))
        wf.onDismissRefusal()
        clockMs += 500
        val effects = wf.onQr("OUTER,BAG")
        assertEquals(emptyList<Effect>(), signals(effects))
    }

    // ---- request outcomes that are not plain accept/refuse ------------------------------------

    @Test fun transientRefusalIsSilentUntilTheAnswer() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(emptyList<Effect>(), signals(wf.onResponse(rejected("processing_paused"))))
        assertEquals(success, signals(wf.onResponse(allocatedAnswer())))
    }

    @Test fun recentRequestExpiredOnScanGivesOneFail() {
        toLoading()
        wf.onQr(validQr)
        clockMs += 60_000
        assertEquals(fail, signals(wf.onResponse(rejected("request_expired"))))
    }

    @Test fun oldRequestExpiredOnPackGivesOneFailAndRecoveryIsSilent() {
        toLoading()
        wf.onQr(validQr)
        clockMs += 6 * 60_000
        val effects = wf.onResponse(rejected("request_expired"))
        assertEquals(fail, signals(effects))
        assertEquals(RequestIntent.RECOVER_ALLOCATION, publishes(effects).single().request.intent)
        val recovered = wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", 3, "75.5")}}""")
            .put("packs", packsArray(listOf("B9", "B1", "B2"))))
        assertEquals(emptyList<Effect>(), signals(recovered))
    }

    @Test fun oldRequestExpiredOnCageTagGivesOneFail() {
        wf.onRfid("TAG-CAGE-007")
        clockMs += 6 * 60_000
        assertEquals(fail, signals(wf.onResponse(rejected("request_expired"))))
    }

    @Test fun messageIdConflictOnScanGivesOneFail() {
        toLoading()
        wf.onQr(validQr)
        assertEquals(fail, signals(wf.onResponse(rejected("message_id_conflict"))))
    }

    @Test fun loginRequiredOnScanGivesOneFailThenSignsOut() {
        toLoading()
        wf.onQr(validQr)
        val effects = wf.onResponse(rejected("login_required"))
        assertEquals(fail, signals(effects))
        assertTrue(effects.last() is Effect.SignOut)
    }

    // ---- no sound: sign-in, badges, buttons, navigation ---------------------------------------

    @Test fun signInAndActiveCageReadAreSilent() {
        val effects = wf.start("s-2") + wf.onResponse(accepted("""{"accepted":true,"isActive":true,
            "load":${loadJson("CAGE-007", 2, "50.4")}}"""))
        assertEquals(emptyList<Effect>(), signals(effects))
    }

    @Test fun badgeTagIsSilent() {
        toLoading()
        assertEquals(emptyList<Effect>(), signals(wf.onRfid("50505501AABBCCDDEEFF001122334455")))
    }

    @Test fun scanWithoutSessionIsSilent() {
        wf.stop()
        assertEquals(emptyList<Effect>(), signals(wf.onRfid("TAG-CAGE-007") + wf.onQr(validQr)))
    }

    @Test fun completeCageButtonOutcomesAreSilent() {
        toLoading()
        wf.onCompleteCage()
        assertEquals(emptyList<Effect>(), signals(wf.onResponse(rejected("cage_not_active"))))
        wf.onDismissRefusal()

        toPreview()
        wf.onConfirmComplete()
        assertEquals(emptyList<Effect>(), signals(wf.onResponse(rejected("cage_contents_changed"))))
        val reread = wf.onDismissRefusal()
        assertEquals(emptyList<Effect>(), signals(reread))
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 2, "50.4")}}"""))

        toPreview()
        wf.onConfirmComplete()
        clockMs += 60_000
        assertEquals(emptyList<Effect>(), signals(wf.onResponse(rejected("request_expired"))))
        wf.onDismissRefusal()

        toPreview()
        wf.onConfirmComplete()
        val done = wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", 2, "50.4", status = "Completed")}}"""))
        assertEquals(emptyList<Effect>(), signals(done))
    }

    @Test fun navigationAndTimersAreSilent() {
        toPreview()
        val effects = wf.onKeepLoading() + wf.onArmExpired() + wf.onStationOnline(false) + wf.onStationOnline(true) +
            wf.onRetryTick("s-1")
        assertEquals(emptyList<Effect>(), signals(effects))
    }

    @Test fun scanOnARefusalOrSummaryScreenIsSilent() {
        toLoading()
        wf.onQr("garbage") // the refusal already sounded once
        assertEquals(emptyList<Effect>(), signals(wf.onQr(validQr) + wf.onRfid("TAG-X")))
        wf.onDismissRefusal()
        toPreview()
        assertEquals(emptyList<Effect>(), signals(wf.onQr(validQr) + wf.onRfid("TAG-X")))
    }
}
