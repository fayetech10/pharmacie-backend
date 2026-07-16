package com.csu.pharmacie.dto;

import com.csu.pharmacie.entity.Regime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class LettreGarantieRequest {
    /** Si renseigné : réutilise un patient existant (issu de la recherche). Sinon, création inline. */
    private String patientId;

    @NotBlank(message = "Le nom du patient est obligatoire")
    private String nom;
    @NotBlank(message = "Le prénom du patient est obligatoire")
    private String prenom;
    @NotBlank(message = "Le téléphone du bénéficiaire est obligatoire")
    private String telephone;
    @NotNull(message = "Le régime est obligatoire")
    private Regime regime;
    private String numeroCni;
    private String numeroAssure;
    /** Date de naissance (extraite de la CNI par OCR ou saisie manuellement). */
    private java.time.LocalDate dateNaissance;
    /** Sexe du bénéficiaire ("M"/"F") — colonne obligatoire du classeur CS_EPS. */
    private String sexe;

    @Valid
    private List<PrestationGarantieDto> prestations;

    @Size(max = 2097152, message = "Image trop volumineuse")
    private String cniRecto;
    @Size(max = 2097152, message = "Image trop volumineuse")
    private String cniVerso;
    /** Carte d'assuré (recto uniquement) — régimes Classique et BSF/CEC. */
    @Size(max = 2097152, message = "Image trop volumineuse")
    private String carteAssureRecto;
    /** Document libre (extrait de naissance pour le régime 0-5 ans). */
    @Size(max = 2097152, message = "Image trop volumineuse")
    private String autreDocument;
    private String autreDocumentTitre;

}
