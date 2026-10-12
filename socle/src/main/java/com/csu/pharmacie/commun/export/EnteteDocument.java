package com.csu.pharmacie.commun.export;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bandeau commun aux documents officiels SEN-CSU (§7 : « respect des modèles officiels »).
 *
 * <p>Regroupe ce qui identifie l'émetteur et la période, pour que les générateurs PDF et
 * Excel n'aient pas à retrouver ces libellés eux-mêmes.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnteteDocument {

    @Builder.Default
    private String republique = "RÉPUBLIQUE DU SÉNÉGAL";
    @Builder.Default
    private String organisme = "AGENCE DE LA COUVERTURE SANITAIRE UNIVERSELLE";
    private String titre;
    private String structureNom;
    private String typeStructureLibelle;
    private String districtNom;
    private String regionNom;
    private String periode;
    private String regimeLibelle;
    /** Numéro du document quand il en porte un (facture récapitulative). */
    private String numero;
}
