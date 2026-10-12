package com.csu.pharmacie.referentiel.controleur;

import com.csu.pharmacie.entity.PrestationHorsCsu;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.csu.pharmacie.commun.securite.ContexteUtilisateur;
import com.csu.pharmacie.commun.web.ExceptionMetier;
import com.csu.pharmacie.referentiel.dto.ResultatImportReferentiel;
import com.csu.pharmacie.referentiel.service.ServiceHorsCsu;

import java.util.List;

/** Référentiel Hors CSU — tarifs des patients payants (§8). */
@RestController
@RequestMapping("/api/hors-csu/tarifs")
@RequiredArgsConstructor
public class ControleurHorsCsu {

    private final ServiceHorsCsu serviceHorsCsu;

    /**
     * Tarifs applicables au point de soins de l'appelant. Sans structure au jeton
     * (compte d'administration), on renvoie le référentiel national.
     */
    @GetMapping("/applicables")
    public List<PrestationHorsCsu> applicables() {
        return serviceHorsCsu.applicables(ContexteUtilisateur.courant().getStructureId());
    }

    @GetMapping
    public List<PrestationHorsCsu> lister() {
        return serviceHorsCsu.lister();
    }

    /** Tarifs que la structure connectée administre elle-même (écran « Paramètres »). */
    @GetMapping("/miens")
    public List<PrestationHorsCsu> miens() {
        return serviceHorsCsu.mesTarifs(ContexteUtilisateur.courant().getStructureId());
    }

    /** Modèle Excel prérempli du catalogue applicable : il ne reste qu'à saisir les prix. */
    @GetMapping("/modele")
    public ResponseEntity<byte[]> modele() {
        String structureId = ContexteUtilisateur.courant().getStructureId();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_tarifs.xlsx\"")
                .contentType(MediaType.valueOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(serviceHorsCsu.modeleImport(structureId));
    }

    @PostMapping
    public PrestationHorsCsu creer(@RequestBody PrestationHorsCsu tarif) {
        tarif.setStructureId(perimetreEcriture(tarif.getStructureId()));
        return serviceHorsCsu.creer(tarif);
    }

    @PutMapping("/{id}")
    public PrestationHorsCsu modifier(@PathVariable String id, @RequestBody PrestationHorsCsu modifications) {
        exigerProprietaire(id);
        return serviceHorsCsu.modifier(id, modifications);
    }

    @PutMapping("/{id}/activation")
    public PrestationHorsCsu activation(@PathVariable String id, @RequestParam boolean actif) {
        exigerProprietaire(id);
        return serviceHorsCsu.basculerActivation(id, actif);
    }

    /**
     * Import Excel. Pour une structure, les tarifs importés sont d'office les siens : le
     * {@code structureId} de la requête n'est honoré que pour un compte d'administration.
     */
    @PostMapping("/import")
    public ResultatImportReferentiel importer(@RequestPart("fichier") MultipartFile fichier,
                                              @RequestParam(required = false) String structureId) {
        return serviceHorsCsu.importer(fichier, perimetreEcriture(structureId));
    }

    /**
     * Périmètre dans lequel l'appelant a le droit d'écrire.
     *
     * <p>Un point de soins écrit dans le sien, quoi qu'il demande : sans cette contrainte,
     * un poste laissant {@code structureId} vide écrivait dans le référentiel
     * <b>national</b>, donc chez tout le monde. Seul un compte d'administration (sans
     * structure au jeton) garde la main sur le national.</p>
     */
    private static String perimetreEcriture(String demande) {
        String structureAppelant = ContexteUtilisateur.courant().getStructureId();
        if (structureAppelant != null && !structureAppelant.isBlank()) {
            return structureAppelant;
        }
        return demande == null || demande.isBlank() ? null : demande;
    }

    /** Une structure ne modifie que ses propres tarifs — jamais le national ni ceux d'autrui. */
    private void exigerProprietaire(String id) {
        String structureAppelant = ContexteUtilisateur.courant().getStructureId();
        if (structureAppelant == null || structureAppelant.isBlank()) {
            return; // compte d'administration : responsable du référentiel national
        }
        String proprietaire = serviceHorsCsu.parId(id).getStructureId();
        if (!structureAppelant.equals(proprietaire)) {
            throw ExceptionMetier.interdit("Ce tarif n'appartient pas à votre structure");
        }
    }
}
