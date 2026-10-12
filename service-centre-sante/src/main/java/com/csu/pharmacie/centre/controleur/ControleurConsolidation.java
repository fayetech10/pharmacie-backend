package com.csu.pharmacie.centre.controleur;

import com.csu.pharmacie.entity.FactureStructure;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.centre.service.ServiceConsolidation;
import com.csu.pharmacie.centre.service.ServiceTableauBord;
import com.csu.pharmacie.entity.Regime;
import com.csu.pharmacie.commun.export.Documents;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Consolidation district (§9) et tableaux de bord (§10). */
@RestController
@RequestMapping("/api/consolidation")
@RequiredArgsConstructor
public class ControleurConsolidation {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ServiceConsolidation serviceConsolidation;
    private final ServiceTableauBord serviceTableauBord;

    /** Factures consolidées d'un district : postes et centres réunis. */
    @GetMapping("/factures")
    public List<FactureStructure> factures(@RequestParam(required = false) String districtId,
                                     @RequestParam(required = false) Integer mois,
                                     @RequestParam(required = false) Integer annee,
                                     @RequestParam(required = false) Regime regime) {
        return serviceConsolidation.factures(districtId, mois, annee, regime);
    }

    @GetMapping("/synthese")
    public ServiceConsolidation.SyntheseDistrict synthese(@RequestParam(required = false) String districtId,
                                                          @RequestParam(required = false) Integer mois,
                                                          @RequestParam(required = false) Integer annee,
                                                          @RequestParam(required = false) Regime regime) {
        return serviceConsolidation.synthese(districtId, mois, annee, regime);
    }

    /** Tableau de bord (§10) : volumétrie, suivi mensuel, structures facturées ou non. */
    @GetMapping("/tableau-bord")
    public ServiceTableauBord.TableauBord tableauBord(@RequestParam(required = false) String districtId,
                                                      @RequestParam(required = false) Integer mois,
                                                      @RequestParam(required = false) Integer annee,
                                                      @RequestParam(required = false) Regime regime) {
        return serviceTableauBord.construire(districtId, mois, annee, regime);
    }

    /** Classeur Excel : une feuille par structure (§9). */
    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> excel(@RequestParam(required = false) String districtId,
                                        @RequestParam(required = false) Integer mois,
                                        @RequestParam(required = false) Integer annee,
                                        @RequestParam(required = false) Regime regime) {
        byte[] contenu = serviceConsolidation.excel(districtId, mois, annee, regime);
        return fichier(contenu, XLSX, nom("consolidation_district", mois, annee, "xlsx"));
    }

    /** PDF de situation globale du district (§9). */
    @GetMapping("/situation.pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam(required = false) String districtId,
                                      @RequestParam(required = false) Integer mois,
                                      @RequestParam(required = false) Integer annee,
                                      @RequestParam(required = false) Regime regime) {
        byte[] contenu = serviceConsolidation.pdf(districtId, mois, annee, regime);
        return fichier(contenu, MediaType.APPLICATION_PDF, nom("situation_district", mois, annee, "pdf"));
    }

    /** Enfants de moins de 5 ans : situation globale du district (RECAP_ENFANTS_5ANS). */
    @GetMapping("/enfants/situation.pdf")
    public ResponseEntity<byte[]> situationEnfants(@RequestParam(required = false) String districtId,
                                                   @RequestParam(required = false) Integer mois,
                                                   @RequestParam(required = false) Integer annee) {
        byte[] contenu = serviceConsolidation.situationEnfants(districtId, mois, annee);
        return fichier(contenu, MediaType.APPLICATION_PDF, nomEnfants("RECAP_ENFANTS_5ANS", mois, annee));
    }

    /** Enfants de moins de 5 ans : liste nominative consolidée du district (LISTE_ENFANTS_5ANS). */
    @GetMapping("/enfants/liste.pdf")
    public ResponseEntity<byte[]> listeEnfants(@RequestParam(required = false) String districtId,
                                               @RequestParam(required = false) Integer mois,
                                               @RequestParam(required = false) Integer annee) {
        byte[] contenu = serviceConsolidation.listeEnfants(districtId, mois, annee);
        return fichier(contenu, MediaType.APPLICATION_PDF, nomEnfants("LISTE_ENFANTS_5ANS_consolide", mois, annee));
    }

    /** « Situation 0-5 ans », 1/2 : le classeur, une feuille par poste / centre (sans synthèse). */
    @GetMapping("/enfants/situation.xlsx")
    public ResponseEntity<byte[]> situationEnfantsExcel(@RequestParam(required = false) String districtId,
                                                        @RequestParam(required = false) Integer mois,
                                                        @RequestParam(required = false) Integer annee) {
        byte[] contenu = serviceConsolidation.excelEnfants(districtId, mois, annee);
        return fichier(contenu, XLSX, nomEnfants("SITUATION_ENFANTS_5ANS", mois, annee, "xlsx"));
    }

    /** « Situation 0-5 ans », 2/2 : la « Liste + facture 0-5 ans » de chaque poste et centre, en un PDF. */
    @GetMapping("/enfants/factures.pdf")
    public ResponseEntity<byte[]> facturesEnfantsPdf(@RequestParam(required = false) String districtId,
                                                     @RequestParam(required = false) Integer mois,
                                                     @RequestParam(required = false) Integer annee) {
        byte[] contenu = serviceConsolidation.facturesEnfantsPdf(districtId, mois, annee);
        return fichier(contenu, MediaType.APPLICATION_PDF, nomEnfants("LISTE_ET_FACTURES_ENFANTS_5ANS", mois, annee));
    }

    /** « RECAP_ENFANTS_5ANS_2026-10.pdf », comme les modèles du district. */
    private static String nomEnfants(String base, Integer mois, Integer annee) {
        return nomEnfants(base, mois, annee, "pdf");
    }

    private static String nomEnfants(String base, Integer mois, Integer annee, String extension) {
        int an = annee == null || annee <= 0 ? java.time.LocalDate.now().getYear() : annee;
        String periode = mois == null || mois < 1 || mois > 12 ? String.valueOf(an) : String.format("%d-%02d", an, mois);
        return Documents.nomFichier(base + "_" + periode, extension);
    }

    private static String nom(String base, Integer mois, Integer annee, String extension) {
        String periode = (mois == null || mois <= 0 ? "" : mois + "_")
                + (annee == null || annee <= 0 ? "" : String.valueOf(annee));
        return Documents.nomFichier(base + (periode.isBlank() ? "" : "_" + periode), extension);
    }

    private static ResponseEntity<byte[]> fichier(byte[] contenu, MediaType type, String nomFichier) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(nomFichier, StandardCharsets.UTF_8).build().toString())
                .body(contenu);
    }
}
