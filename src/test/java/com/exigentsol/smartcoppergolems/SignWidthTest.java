package com.exigentsol.smartcoppergolems;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SignWidthTest {

    @Test
    void shortLabelsFit() {
        assertTrue(ChestReports.fitsOnSign("[logs]"));
        assertTrue(ChestReports.fitsOnSign("[mineral blocks]"));
        assertTrue(ChestReports.fitsOnSign("[animal gear]"));
    }

    @Test
    void longLabelsDoNot() {
        assertFalse(ChestReports.fitsOnSign("[smithing templates]"));
        assertFalse(ChestReports.fitsOnSign("[concrete and terracotta]"));
    }
}
