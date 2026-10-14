package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.dto.DistrictDto;
import com.csu.pharmacie.commun.export.GenerateurExcel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.export.GenerateurPdfEnfantsDistrict;
import com.csu.pharmacie.commun.securite.UtilisateurAuthentifie;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.entity.FactureStructure;
import com.csu.pharmacie.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consolidation d'un district (14/10/2026) : le service régional ne consolide qu'un district de
 * SA région — la région du district est relue au service identité, l'identifiant demandé ne
 * suffit pas. Le niveau central consolide n'importe quel district ; le district, le sien.
 */
class ConsolidationRegionTest {

    private static final String THIES = "region-thies";

    private final ServiceFactureCentre serviceFacture = mock(ServiceFactureCentre.class);
    private final ClientIdentite clientIdentite = mock(ClientIdentite.class);
    private final ServiceConsolidation service = new ServiceConsolidation(serviceFacture, clientIdentite,
            mock(GenerateurExcel.class), mock(GenerateurPdf.class), mock(GenerateurPdfEnfantsDistrict.class));
    private final List<FactureStructure> factures = List.of(new FactureStructure());

    @BeforeEach
    void preparer() {
        when(clientIdentite.district("d-thies")).thenReturn(DistrictDto.builder()
                .id("d-thies").nom("District de Thiès").regionId(THIES).build());
        when(clientIdentite.district("d-dakar")).thenReturn(DistrictDto.builder()
                .id("d-dakar").nom("District de Dakar Centre").regionId("region-dakar").build());
        when(serviceFacture.facturesDuDistrict(anyString(), any(), any(), any())).thenReturn(factures);
        connecter(Role.SERVICE_REGIONAL, THIES, null);
    }

    @AfterEach
    void deconnecter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void leServiceRegionalConsolideUnDistrictDeSaRegion() {
        assertSame(factures, service.factures("d-thies", 10, 2026, null));
    }

    @Test
    void unDistrictDUneAutreRegionEstRefuse() {
        refuse(() -> service.factures("d-dakar", 10, 2026, null));
        refuse(() -> service.synthese("d-dakar", 10, 2026, null));
        refuse(() -> service.excel("d-dakar", 10, 2026, null));
        refuse(() -> service.listeEnfants("d-dakar", 10, 2026));
        verify(serviceFacture, never()).facturesDuDistrict(eq("d-dakar"), any(), any(), any());
        verify(clientIdentite, never()).structuresDuDistrict("d-dakar");
    }

    @Test
    void unDistrictInconnuEstRefusePlutotQuOuvert() {
        refuse(() -> service.factures("d-fantome", 10, 2026, null));
    }

    @Test
    void unServiceRegionalSansRegionNeConsolideRien() {
        connecter(Role.SERVICE_REGIONAL, null, null);
        refuse(() -> service.factures("d-thies", 10, 2026, null));
    }

    @Test
    void leNiveauCentralConsolideToutDistrict() {
        connecter(Role.SERVICE_CENTRAL, null, null);
        assertSame(factures, service.factures("d-dakar", 10, 2026, null));
        verify(clientIdentite, never()).district(anyString());
    }

    @Test
    void leDistrictNeConsolideQueLeSien() {
        connecter(Role.DISTRICT, THIES, "d-thies");
        assertSame(factures, service.factures(null, 10, 2026, null));
        refuse(() -> service.factures("d-dakar", 10, 2026, null));
    }

    // ─────────────────────── fabrication ───────────────────────

    private static void refuse(Executable appel) {
        ExceptionMetier refus = assertThrows(ExceptionMetier.class, appel);
        assertEquals(HttpStatus.FORBIDDEN, refus.getStatut(), refus.getMessage());
    }

    private static void connecter(Role role, String regionId, String districtId) {
        UtilisateurAuthentifie utilisateur = UtilisateurAuthentifie.builder()
                .id("u-1").prenom("Service").nom("Régional").role(role)
                .regionId(regionId).districtId(districtId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utilisateur, null, List.of()));
    }
}
