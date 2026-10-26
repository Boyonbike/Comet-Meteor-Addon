package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyLocationTest {
    @Test void onlyAuthenticatesWhereNeededAndNeverRelogsInOnTheRoute() {
        assertTrue(LobbyLocation.CRACKED_LOBBY.requiresLogin(false, false));
        assertTrue(LobbyLocation.CRACKED_LOBBY.requiresLogin(false, true));
        assertFalse(LobbyLocation.LOBBY.requiresLogin(true, false));
        assertFalse(LobbyLocation.WAITING_AREA.requiresLogin(true, false));
        assertTrue(LobbyLocation.NONE.requiresLogin(true, false));
        assertFalse(LobbyLocation.NONE.requiresLogin(false, false));
        assertFalse(LobbyLocation.NONE.requiresLogin(true, true));
    }

    @Test void identifiesEachSpawnInItsDimension() {
        assertEquals(LobbyLocation.CRACKED_LOBBY, LobbyLocation.detect("minecraft:the_end", 1000.5, 100, 1000.5));
        assertEquals(LobbyLocation.LOBBY, LobbyLocation.detect("minecraft:overworld", 247.5, 62, 228));
        assertEquals(LobbyLocation.WAITING_AREA, LobbyLocation.detect("minecraft:the_end", 29.5, 19.06, 18.5));
    }

    @Test void recognisesNudgesToEveryCornerOfTheThreeBlockSquareRadius() {
        for (int dx : new int[] {-3, 3}) {
            for (int dz : new int[] {-3, 3}) {
                assertEquals(LobbyLocation.CRACKED_LOBBY,
                    LobbyLocation.detect("minecraft:the_end", 1000.5 + dx, 100, 1000.5 + dz));
                assertEquals(LobbyLocation.LOBBY,
                    LobbyLocation.detect("minecraft:overworld", 247.5 + dx, 62, 228 + dz));
                assertEquals(LobbyLocation.WAITING_AREA,
                    LobbyLocation.detect("minecraft:the_end", 29.5 + dx, 19.06, 18.5 + dz));
            }
        }
    }

    @Test void rejectsPositionsJustBeyondTheSquare() {
        for (double offset : new double[] {-3.01, 3.01}) {
            assertEquals(LobbyLocation.NONE,
                LobbyLocation.detect("minecraft:overworld", 247.5 + offset, 62, 228));
            assertEquals(LobbyLocation.NONE,
                LobbyLocation.detect("minecraft:overworld", 247.5, 62, 228 + offset));
        }
    }

    @Test void allowsSmallSpawnRoundingAndMovement() {
        assertEquals(LobbyLocation.WAITING_AREA, LobbyLocation.detect("minecraft:the_end", 29.6, 19, 18.4));
        assertEquals(LobbyLocation.CRACKED_LOBBY, LobbyLocation.detect("minecraft:the_end", 1001, 100.1, 1001));
    }

    @Test void doesNotConfuseRouteCoordinatesOrOtherDimensionsWithLobbies() {
        assertEquals(LobbyLocation.NONE, LobbyLocation.detect("minecraft:overworld", 1000.5, 100, 1000.5));
        assertEquals(LobbyLocation.NONE, LobbyLocation.detect("minecraft:the_nether", 247.5, 62, 228));
        assertEquals(LobbyLocation.NONE, LobbyLocation.detect("minecraft:the_end", 29.5, 30, 18.5));
        assertEquals(LobbyLocation.NONE, LobbyLocation.detect("minecraft:the_end", 40, 19.06, 18.5));
        assertEquals(LobbyLocation.NONE, LobbyLocation.detect(null, 29.5, 19.06, 18.5));
    }
}
