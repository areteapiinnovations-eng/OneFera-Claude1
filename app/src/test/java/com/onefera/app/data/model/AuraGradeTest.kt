package com.onefera.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AuraGradeTest {
    @Test
    fun `maps points to grades at the documented thresholds`() {
        assertEquals(AuraGrade.C, AuraGrade.forPoints(0))
        assertEquals(AuraGrade.C, AuraGrade.forPoints(299))
        assertEquals(AuraGrade.B, AuraGrade.forPoints(300))
        assertEquals(AuraGrade.A, AuraGrade.forPoints(525))
        assertEquals(AuraGrade.S, AuraGrade.forPoints(700))
        assertEquals(AuraGrade.SSS, AuraGrade.forPoints(900))
        assertEquals(AuraGrade.SSS, AuraGrade.forPoints(5000))
    }
}
