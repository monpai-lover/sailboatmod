package com.monpai.sailboatmod;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The docs a new contributor reads first must not contradict the code (they once said protocol 11 after it became 12). */
class HandoffDocsConsistencyTest {
    private static final List<String> ENTRY_DOCS = List.of(
            "AGENTS.md", "CLAUDE.md", "README.md", "START_HERE.md", "docs/handoff/LDLIB2_INTEGRATION.md");
    private static final Pattern PROTOCOL_MENTION = Pattern.compile(
            "(?i)(?:protocol(?: version)?(?: is)?\\s*\\(?[\"*]*|協定(?:是|為|仍為)\\s*\\**\\s*)(\\d+)");

    private static String modNetwork() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));
    }

    @Test
    void entryDocsStateTheProtocolVersionTheCodeUses() throws Exception {
        Matcher code = Pattern.compile("PROTOCOL_VERSION = \"(\\d+)\"").matcher(modNetwork());
        assertTrue(code.find(), "ModNetwork declares PROTOCOL_VERSION");
        List<String> wrong = new ArrayList<>();
        int mentions = 0;
        for (String doc : ENTRY_DOCS) {
            Matcher mention = PROTOCOL_MENTION.matcher(Files.readString(Path.of(doc)));
            while (mention.find()) {
                mentions++;
                if (!mention.group(1).equals(code.group(1))) {
                    wrong.add(doc + ": \"" + mention.group().trim() + "\"");
                }
            }
        }
        assertTrue(mentions >= ENTRY_DOCS.size(), "every entry doc names the protocol version");
        assertEquals(List.of(), wrong, "code uses protocol " + code.group(1));
    }

    @Test
    void agentsGuideCountsTheRegisteredPayloads() throws Exception {
        long registered = Pattern.compile("CHANNEL\\.registerMessage\\(").matcher(modNetwork()).results().count();
        assertTrue(Files.readString(Path.of("AGENTS.md")).contains("registers " + registered + " directional custom payloads"),
                "AGENTS.md should say " + registered + " payloads");
    }
}
