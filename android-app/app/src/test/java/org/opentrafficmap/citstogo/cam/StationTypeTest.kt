package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTypeTest {
    @Test
    fun selectableTypesMatchSupportedVehicleTypes() {
        assertEquals(
            StationType.entries.filterNot { it == StationType.TRAILER || it == StationType.ROAD_SIDE_UNIT },
            StationType.selectable,
        )
        assertTrue(StationType.PEDESTRIAN in StationType.selectable)
        assertTrue(StationType.CYCLIST in StationType.selectable)
        assertTrue(StationType.PASSENGER_CAR in StationType.selectable)
        assertFalse(StationType.TRAILER in StationType.selectable)
        assertFalse(StationType.ROAD_SIDE_UNIT in StationType.selectable)
    }

    @Test
    fun nonSelectableCodesFallBackToPedestrian() {
        assertEquals(StationType.CYCLIST, StationType.selectableFromCode(StationType.CYCLIST.code))
        assertEquals(StationType.PASSENGER_CAR, StationType.selectableFromCode(StationType.PASSENGER_CAR.code))
        assertEquals(StationType.PEDESTRIAN, StationType.selectableFromCode(StationType.TRAILER.code))
        assertEquals(StationType.PEDESTRIAN, StationType.selectableFromCode(255))
    }
}
