package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.*

@Service
class SchedulingService(private val clock: Clock) {
    val zone: ZoneId = ZoneId.of("Asia/Jakarta")
    fun now(): ZonedDateTime = ZonedDateTime.now(clock.withZone(zone))
    fun parse(slot: String): Pair<LocalTime, LocalTime> {
        if (!Regex("[0-9]{2}:[0-9]{2}-[0-9]{2}:[0-9]{2}").matches(slot)) invalid("Slot harus HH:mm-HH:mm.")
        val start = try { LocalTime.parse(slot.substring(0, 5)) } catch (_: Exception) { invalid("Jam mulai tidak valid.") }
        val end = try { LocalTime.parse(slot.substring(6, 11)) } catch (_: Exception) { invalid("Jam akhir tidak valid.") }
        if (start >= end) invalid("Slot harus berakhir setelah mulai pada hari yang sama.")
        return start to end
    }
    fun normalizeSlots(values: List<String?>): List<String> {
        if (values.size > 100) invalid("Maksimal 100 slot waktu.")
        val clean = values.map { it?.trim() ?: invalid("Slot tidak boleh null.") }
        if (clean.distinct().size != clean.size) invalid("Slot waktu duplikat.")
        val sorted = clean.map { it to parse(it) }.sortedBy { it.second.first }
        if (sorted.zipWithNext().any { (a,b) -> a.second.second > b.second.first }) invalid("Slot waktu tidak boleh bertumpang tindih.")
        return sorted.map { it.first }
    }
    fun slotsOn(date: LocalDate, allowed: List<String>, at: ZonedDateTime = now()): List<String> =
        normalizeSlots(allowed).filter { date > at.toLocalDate() || (date == at.toLocalDate() && parse(it).first > at.toLocalTime()) }

    fun validate(date: LocalDate, slot: String, readyDates: List<LocalDate>, allowedSlots: List<String>) {
        val at = now()
        if (readyDates.isEmpty() || date < at.toLocalDate() || readyDates.any { date < it }) invalid("Jadwal sebelum tanggal tersedia atau tanggal hari ini.")
        if (slot !in slotsOn(date, allowedSlots, at)) invalid("Slot tidak tersedia atau sudah dimulai.")
    }
    fun requireFuture(date: LocalDate, slot: String) {
        if (!date.atTime(parse(slot).first).atZone(zone).isAfter(now()))
            throw ApiException(HttpStatus.CONFLICT, "SCHEDULE_ELAPSED", "Jadwal sudah dimulai. Ajukan jadwal baru dan minta persetujuan pembeli sebelum pembayaran.")
    }
    fun requireStarted(date: LocalDate, slot: String, readyDates: List<LocalDate>) {
        if (readyDates.isEmpty() || readyDates.any { date < it }) conflict("Jadwal pesanan tidak konsisten dengan snapshot tanggal siap.")
        if (now().isBefore(date.atTime(parse(slot).first).atZone(zone)))
            throw ApiException(HttpStatus.CONFLICT, "SCHEDULE_NOT_STARTED", "Pemenuhan belum dapat dimulai sebelum jadwal pesanan dalam WIB.")
    }
}
