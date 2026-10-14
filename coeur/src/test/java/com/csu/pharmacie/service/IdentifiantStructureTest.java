package com.csu.pharmacie.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentifiantStructureTest {

    @Test
    void identifiantDUneNouvelleStructureEstSonCodeEnMajuscules() {
        assertEquals("THPS121", StructureSanitaireService.identifiantDeBase("thps121"));
        assertEquals("DKCS01", StructureSanitaireService.identifiantDeBase(" DK-CS01 "));
        assertEquals("STR", StructureSanitaireService.identifiantDeBase(null));
    }
}
