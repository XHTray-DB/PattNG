package com.v2ray.ang.v2get

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class V2GetNoticesTest {
    private val today = LocalDate.of(2026, 11, 5)

    private fun session(used: Long, quota: Long = 100L, expires: String = "2026-12-31") =
        V2GetSession("t", "u", quota, used, expires)

    @Test
    fun nothingWhenThereIsPlentyLeft() = assertNull(V2GetNotices.alertFor(session(10), today))

    @Test
    fun quotaWarningsComeAtEightyAndNinetyPercent() {
        assertEquals(V2GetAlertKind.QUOTA_LOW, V2GetNotices.alertFor(session(82), today)?.kind)
        val almost = V2GetNotices.alertFor(session(93), today)
        assertEquals(V2GetAlertKind.QUOTA_ALMOST, almost?.kind)
        assertEquals(7, almost?.percentLeft)
    }

    @Test
    fun theEndOfTheSubscriptionIsToldThreeDaysAhead() {
        val soon = V2GetNotices.alertFor(session(10, expires = "2026-11-07"), today)
        assertEquals(V2GetAlertKind.EXPIRY_SOON, soon?.kind)
        assertEquals(2, soon?.daysLeft)
        assertNull(V2GetNotices.alertFor(session(10, expires = "2026-11-09"), today))
    }

    @Test
    fun anAccountAlreadyOutOfQuotaOrTimeHasNoWarning() {
        assertNull(V2GetNotices.alertFor(session(100), today))
        assertNull(V2GetNotices.alertFor(session(10, expires = "2026-11-01"), today))
        assertNull(V2GetNotices.alertFor(null, today))
    }

    @Test
    fun unlimitedQuotaOnlyWarnsAboutTime() {
        assertNull(V2GetNotices.alertFor(session(10_000, quota = 0), today))
        assertEquals(V2GetAlertKind.EXPIRY_SOON, V2GetNotices.alertFor(session(10_000, quota = 0, expires = "2026-11-06"), today)?.kind)
    }
}
