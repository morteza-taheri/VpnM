package vn.unlimit.vpngate.utils

import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Minimal Gregorian <-> Jalali (Persian solar) calendar converter, plus a formatter used for
 * showing dates (e.g. "server list last updated") in either calendar depending on the user's
 * selected app language.
 *
 * The conversion uses the standard 33-year leap-cycle algorithm (accurate for any date in the
 * Gregorian era used by this app, i.e. no external library dependency needed).
 */
object JalaliDateUtil {

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    private val jalaliMonthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    fun toJalali(date: Date): JalaliDate {
        val cal = Calendar.getInstance()
        cal.time = date
        return gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): JalaliDate {
        val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gy2 = if (gm > 2) (gy + 1) else gy
        val days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        var remaining = days % 12053
        jy += 4 * (remaining / 1461)
        remaining %= 1461
        if (remaining > 365) {
            jy += (remaining - 1) / 365
            remaining = (remaining - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (remaining < 186) {
            jm = 1 + (remaining / 31)
            jd = 1 + (remaining % 31)
        } else {
            jm = 7 + ((remaining - 186) / 30)
            jd = 1 + ((remaining - 186) % 30)
        }
        return JalaliDate(jy, jm, jd)
    }

    private fun toPersianDigits(number: Int): String {
        return number.toString().map { ch ->
            if (ch.isDigit()) persianDigits[ch - '0'] else ch
        }.joinToString("")
    }

    /**
     * Formats [date] as a date+time string appropriate for [isPersian]:
     * - Persian: Jalali calendar, Persian digits, Persian month name (e.g. "۱۲ مرداد ۱۴۰۴ - ۱۴:۳۰")
     * - Otherwise: standard Gregorian "yyyy-MM-dd HH:mm" in the default locale
     */
    fun format(date: Date, isPersian: Boolean): String {
        return if (isPersian) {
            val j = toJalali(date)
            val cal = Calendar.getInstance().apply { time = date }
            val hour = toPersianDigits(cal.get(Calendar.HOUR_OF_DAY)).padStart(2, '۰')
            val minute = toPersianDigits(cal.get(Calendar.MINUTE)).padStart(2, '۰')
            "${toPersianDigits(j.day)} ${jalaliMonthNames[j.month - 1]} ${toPersianDigits(j.year)} - $hour:$minute"
        } else {
            val cal = Calendar.getInstance().apply { time = date }
            String.format(
                Locale.US, "%04d-%02d-%02d %02d:%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
            )
        }
    }
}
