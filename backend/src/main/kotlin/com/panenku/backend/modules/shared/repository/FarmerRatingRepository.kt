package com.panenku.backend.modules.shared.repository

import com.panenku.backend.modules.shared.dto.FarmerRatingDTO
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.RoundingMode
import java.util.UUID

/** Read-only F1 summary. Review submission/ownership enforcement remains in the review module. */
@Repository
class FarmerRatingRepository(private val jdbc: JdbcTemplate) {
    fun summary(farmerId: UUID): FarmerRatingDTO {
        val rows = jdbc.query("""SELECT avg(r.rating) AS average, count(*) AS review_count
            FROM reviews r JOIN orders o ON o.id = r.order_id
            WHERE r.farmer_id = ? AND o.farmer_id = r.farmer_id AND o.buyer_id = r.buyer_id
              AND o.order_status = 'selesai'""", { rs, _ ->
            FarmerRatingDTO(rs.getBigDecimal("average")?.setScale(2, RoundingMode.HALF_UP), rs.getLong("review_count"))
        }, farmerId)
        return rows.single()
    }
}
