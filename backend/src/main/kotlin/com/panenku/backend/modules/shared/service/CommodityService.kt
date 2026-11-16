package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.*
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.dto.*
import com.panenku.backend.modules.shared.entity.Commodity
import com.panenku.backend.modules.shared.repository.CommodityRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CommodityService(private val repository: CommodityRepository, private val actors: ActorService,
                       private val db: JdbcTemplate, private val mapper: ObjectMapper, private val clock: Clock) {
    fun list(actor: UUID): List<CommodityDTO> {
        actors.require(actor, "admin")
        return repository.findAll().sortedBy { it.name }.map { CommodityDTO(it.id, it.name, it.isActive) }
    }
    fun save(actor: UUID, id: UUID?, request: CommodityRequest): CommodityDTO {
        actors.requireLocked(actor, "admin")
        val name = request.name.trim()
        if (name.isBlank() || name.length > 100) invalid("Nama komoditas wajib, maksimal 100 karakter.")
        val entity = id?.let { repository.lockById(it) ?: missing("Komoditas tidak ditemukan.") } ?: Commodity()
        if (!entity.name.equals(name, ignoreCase = true) && repository.existsByNameIgnoreCase(name)) conflict("Nama komoditas sudah dipakai.")
        val previous = if (id == null) null else mapper.writeValueAsString(mapOf("name" to entity.name, "isActive" to entity.isActive))
        val changed = id == null || entity.name != name || entity.isActive != request.isActive
        entity.name = name; entity.isActive = request.isActive
        repository.saveAndFlush(entity)
        if (changed) {
            val after = mapper.writeValueAsString(mapOf("name" to entity.name, "isActive" to entity.isActive))
            db.update("""INSERT INTO audit_logs(id,actor_id,actor_role,action,target_type,target_id,old_values,new_values,created_at)
                VALUES (?,?,'admin',?,'commodity',?,?,?,?)""", UUID.randomUUID(), actor,
                if (id == null) "COMMODITY_CREATED" else "COMMODITY_UPDATED", entity.id, previous, after,
                java.sql.Timestamp.from(clock.instant()))
        }
        return CommodityDTO(entity.id, entity.name, entity.isActive)
    }
}
