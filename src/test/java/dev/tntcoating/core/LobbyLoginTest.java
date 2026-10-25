package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LobbyLoginTest {
    @Test void buildsLoginCommandWithoutSlash() {
        assertEquals("login hunter2", LobbyLogin.command("hunter2"));
    }

    @Test void blankPasswordSendsNothing() {
        assertNull(LobbyLogin.command(null));
        assertNull(LobbyLogin.command(""));
        assertNull(LobbyLogin.command("   "));
    }

    @Test void redactsThePasswordFromServerReplies() {
        assertEquals("Wrong password: ********", LobbyLogin.redact("Wrong password: hunter2", "hunter2"));
        assertEquals("Wrong password: hunter2", LobbyLogin.redact("Wrong password: hunter2", ""));
        assertEquals("Wrong password: hunter2", LobbyLogin.redact("Wrong password: hunter2", null));
        assertEquals("", LobbyLogin.redact(null, "hunter2"));
    }

    @Test void recognisesCommonSuccessReplies() {
        for (String line : new String[] {
            "You are now logged in!",
            "You have been logged in!",
            "Successfully logged in.",
            "Logged in successfully!",
            "Login successful.",
            "You have been successfully logged in!",
            "You are already logged in!",
            "Already logged in.",
            "Authentication successful.",
            "Successfully authenticated."
        }) {
            assertEquals(LobbyLogin.Result.SUCCESS, LobbyLogin.classify(line), line);
        }
    }

    @Test void recognisesCommonFailureReplies() {
        for (String line : new String[] {
            "Wrong password!",
            "Incorrect password, please try again.",
            "Invalid password!",
            "The password is incorrect.",
            "Invalid credentials.",
            "Login failed.",
            "Failed to log in.",
            "Authentication failed.",
            "You are not registered!",
            "Please register first.",
            "Unknown account.",
            "Account not found.",
            "Too many login attempts.",
            "Please wait before trying again."
        }) {
            assertEquals(LobbyLogin.Result.FAILURE, LobbyLogin.classify(line), line);
        }
    }

    @Test void stripsFormattingBeforeMatching() {
        assertEquals(LobbyLogin.Result.SUCCESS, LobbyLogin.classify("§aYou are now logged in!"));
        assertEquals(LobbyLogin.Result.FAILURE, LobbyLogin.classify("§cWrong password!"));
    }

    @Test void ignoresPromptsAndUnrelatedLines() {
        for (String line : new String[] {
            "Please log in with /login <password>.",
            "Usage: /login <password>",
            "Welcome to the server!",
            "You are not logged in yet, but you are authenticated.",
            "Steve joined the game.",
            ""
        }) {
            assertEquals(LobbyLogin.Result.IGNORED, LobbyLogin.classify(line), line);
        }
    }

    @Test void matchingIsCaseInsensitive() {
        assertEquals(LobbyLogin.Result.SUCCESS, LobbyLogin.classify("YOU ARE NOW LOGGED IN"));
        assertEquals(LobbyLogin.Result.FAILURE, LobbyLogin.classify("WRONG PASSWORD"));
    }
}
