package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.usage.RawUsageStat
import com.niqob.app.data.repository.AndroidUsageStatsRepository
import com.niqob.app.domain.model.AppUsageInfo
import com.niqob.app.domain.model.DailyUsageSummary
import com.niqob.app.fakes.FakeUsageStatsDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UsageStatsTrackingTest {

    private lateinit var fakeDataSource: FakeUsageStatsDataSource
    private lateinit var repository: AndroidUsageStatsRepository

    private val testTimeZone: TimeZone = TimeZone.getTimeZone("UTC")
    // Fixed reference time: 2026-10-02 14:30:00.000 UTC
    private val fixedNowMillis: Long = 1790951400000L

    @Before
    fun setUp() {
        fakeDataSource = FakeUsageStatsDataSource(permissionGranted = true)
        repository = AndroidUsageStatsRepository(
            dataSource = fakeDataSource,
            selfPackageName = "com.niqob.app",
            timeProvider = { fixedNowMillis },
            timeZone = testTimeZone
        )
    }

    // ==========================================
    // 1. Usage Model Tests
    // ==========================================

    @Test
    fun appUsageInfo_unitConversions_areAccurate() {
        val appUsage = AppUsageInfo(
            packageName = "com.android.chrome",
            totalTimeInForegroundMillis = 150_000L, // 2.5 minutes
            usageDate = "2026-10-02",
            lastTimeUsed = fixedNowMillis,
            appLabel = "Chrome"
        )

        assertEquals("com.android.chrome", appUsage.packageName)
        assertEquals(150_000L, appUsage.totalTimeInForegroundMillis)
        assertEquals(2, appUsage.totalTimeInForegroundMinutes)
        assertEquals(150L, appUsage.totalTimeInForegroundSeconds)
        assertEquals("2026-10-02", appUsage.usageDate)
        assertEquals("Chrome", appUsage.appLabel)
    }

    @Test
    fun dailyUsageSummary_conversionsAndAggregation_areAccurate() {
        val app1 = AppUsageInfo("com.game.one", 3_600_000L, "2026-10-02") // 60 min (1 hr)
        val app2 = AppUsageInfo("com.game.two", 1_800_000L, "2026-10-02") // 30 min (0.5 hr)

        val summary = DailyUsageSummary(
            date = "2026-10-02",
            totalScreenTimeMillis = 5_400_000L, // 90 min (1.5 hr)
            appUsages = listOf(app1, app2),
            startTimeMillis = 1000L,
            endTimeMillis = 2000L
        )

        assertEquals("2026-10-02", summary.date)
        assertEquals(5_400_000L, summary.totalScreenTimeMillis)
        assertEquals(90, summary.totalScreenTimeMinutes)
        assertEquals(1.5, summary.totalScreenTimeHours, 0.001)
        assertEquals(2, summary.appUsages.size)
    }

    // ==========================================
    // 2. Date / Day Handling Tests
    // ==========================================

    @Test
    fun getTodayUsage_calculatesStartOfDayAtMidnightCorrectly() = runBlocking {
        // fixedNowMillis is 2026-10-02 14:30:00.000 UTC
        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        assertEquals("2026-10-02", summary.date)

        val expectedMidnight = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = fixedNowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(expectedMidnight, summary.startTimeMillis)
        assertEquals(fixedNowMillis, summary.endTimeMillis)
    }

    // ==========================================
    // 3. Empty Usage Result Tests
    // ==========================================

    @Test
    fun getTodayUsage_whenNoStatsExist_returnsEmptySummary() = runBlocking {
        fakeDataSource.clear()

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        assertEquals(0L, summary.totalScreenTimeMillis)
        assertEquals(0, summary.totalScreenTimeMinutes)
        assertTrue(summary.appUsages.isEmpty())
    }

    @Test
    fun getTodayUsage_whenStatsHaveZeroDurationOrBlankPackages_filtersThemOut() = runBlocking {
        fakeDataSource.setStats(
            listOf(
                RawUsageStat(packageName = "com.empty.app", totalTimeInForeground = 0L),
                RawUsageStat(packageName = "   ", totalTimeInForeground = 50_000L),
                RawUsageStat(packageName = "", totalTimeInForeground = 10_000L),
                RawUsageStat(packageName = "com.negative.app", totalTimeInForeground = -100L)
            )
        )

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        assertEquals(0L, summary.totalScreenTimeMillis)
        assertTrue(summary.appUsages.isEmpty())
    }

    // ==========================================
    // 4. Multiple Apps Aggregation & Sorting Tests
    // ==========================================

    @Test
    fun getTodayUsage_multipleApps_aggregatesAndSortsDescending() = runBlocking {
        fakeDataSource.setStats(
            listOf(
                RawUsageStat(
                    packageName = "com.google.android.youtube",
                    totalTimeInForeground = 1_200_000L, // 20 min
                    lastTimeUsed = 1000L
                ),
                RawUsageStat(
                    packageName = "com.mojang.minecraftpe",
                    totalTimeInForeground = 2_400_000L, // 40 min
                    lastTimeUsed = 2000L
                ),
                RawUsageStat(
                    packageName = "com.android.chrome",
                    totalTimeInForeground = 600_000L, // 10 min
                    lastTimeUsed = 500L
                )
            )
        )

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        // Total = 20 + 40 + 10 = 70 min = 4,200,000 ms
        assertEquals(4_200_000L, summary.totalScreenTimeMillis)
        assertEquals(70, summary.totalScreenTimeMinutes)
        assertEquals(3, summary.appUsages.size)

        // Verify descending sort by duration
        assertEquals("com.mojang.minecraftpe", summary.appUsages[0].packageName)
        assertEquals(2_400_000L, summary.appUsages[0].totalTimeInForegroundMillis)
        assertEquals(40, summary.appUsages[0].totalTimeInForegroundMinutes)

        assertEquals("com.google.android.youtube", summary.appUsages[1].packageName)
        assertEquals(1_200_000L, summary.appUsages[1].totalTimeInForegroundMillis)
        assertEquals(20, summary.appUsages[1].totalTimeInForegroundMinutes)

        assertEquals("com.android.chrome", summary.appUsages[2].packageName)
        assertEquals(600_000L, summary.appUsages[2].totalTimeInForegroundMillis)
        assertEquals(10, summary.appUsages[2].totalTimeInForegroundMinutes)
    }

    // ==========================================
    // 5. Multiple Intervals / Same App Consolidation
    // ==========================================

    @Test
    fun getTodayUsage_sameAppAcrossMultipleIntervals_consolidatesAndKeepsLatestLastUsed() = runBlocking {
        fakeDataSource.setStats(
            listOf(
                RawUsageStat(
                    packageName = "com.google.android.youtube",
                    totalTimeInForeground = 500_000L,
                    lastTimeUsed = 1000L
                ),
                RawUsageStat(
                    packageName = "com.google.android.youtube",
                    totalTimeInForeground = 700_000L,
                    lastTimeUsed = 3500L // Newer
                ),
                RawUsageStat(
                    packageName = "com.google.android.youtube",
                    totalTimeInForeground = 100_000L,
                    lastTimeUsed = 2000L // Older than 3500
                )
            )
        )

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        assertEquals(1, summary.appUsages.size)
        val youtubeUsage = summary.appUsages.first()

        assertEquals("com.google.android.youtube", youtubeUsage.packageName)
        assertEquals(1_300_000L, youtubeUsage.totalTimeInForegroundMillis)
        assertEquals(3500L, youtubeUsage.lastTimeUsed)
        assertEquals(1_300_000L, summary.totalScreenTimeMillis)
    }

    // ==========================================
    // 6. Exclusion of NIQOB Self-Usage
    // ==========================================

    @Test
    fun getTodayUsage_whenExcludeSelfIsTrue_excludesNiqobPackage() = runBlocking {
        fakeDataSource.setStats(
            listOf(
                RawUsageStat(
                    packageName = "com.niqob.app",
                    totalTimeInForeground = 900_000L // 15 min
                ),
                RawUsageStat(
                    packageName = "com.example.childgame",
                    totalTimeInForeground = 1_800_000L // 30 min
                )
            )
        )

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        assertEquals(1, summary.appUsages.size)
        assertEquals("com.example.childgame", summary.appUsages.first().packageName)
        assertEquals(1_800_000L, summary.totalScreenTimeMillis)
    }

    @Test
    fun getTodayUsage_whenExcludeSelfIsFalse_includesNiqobPackage() = runBlocking {
        fakeDataSource.setStats(
            listOf(
                RawUsageStat(
                    packageName = "com.niqob.app",
                    totalTimeInForeground = 900_000L
                ),
                RawUsageStat(
                    packageName = "com.example.childgame",
                    totalTimeInForeground = 1_800_000L
                )
            )
        )

        val result = repository.getTodayUsage(excludeSelf = false)
        assertTrue(result is Result.Success)

        val summary = (result as Result.Success).data
        assertEquals(2, summary.appUsages.size)
        assertEquals(2_700_000L, summary.totalScreenTimeMillis)
    }

    // ==========================================
    // 7. Permission and Error Handling
    // ==========================================

    @Test
    fun hasUsageAccessPermission_delegatesToDataSource() {
        fakeDataSource.permissionGranted = true
        assertTrue(repository.hasUsageAccessPermission())

        fakeDataSource.permissionGranted = false
        assertFalse(repository.hasUsageAccessPermission())
    }

    @Test
    fun getTodayUsage_whenPermissionDenied_returnsSecurityException() = runBlocking {
        fakeDataSource.permissionGranted = false

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Error)

        val error = (result as Result.Error).exception
        assertTrue(error is SecurityException)
        assertTrue(error.message!!.contains("PACKAGE_USAGE_STATS"))
    }

    @Test
    fun getUsageForRange_whenStartTimeAfterEndTime_returnsIllegalArgumentException() = runBlocking {
        val result = repository.getUsageForRange(
            startTimeMillis = 2000L,
            endTimeMillis = 1000L,
            excludeSelf = true
        )
        assertTrue(result is Result.Error)

        val error = (result as Result.Error).exception
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun getTodayUsage_whenDataSourceThrows_returnsErrorResult() = runBlocking {
        fakeDataSource.shouldThrowOnQuery = true

        val result = repository.getTodayUsage(excludeSelf = true)
        assertTrue(result is Result.Error)
        val error = (result as Result.Error).exception
        assertTrue(error.message!!.contains("Failed to query usage statistics"))
    }
}
