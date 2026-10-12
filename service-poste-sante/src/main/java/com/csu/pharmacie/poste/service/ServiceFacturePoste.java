package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;
import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.facturation.DepotFeuilleSoins;
import com.csu.pharmacie.commun.facturation.DepotLettreGarantie;
import com.csu.pharmacie.commun.facturation.DepotPieceDossier;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.facturation.ServiceFacturation;
import org.springframework.stereotype.Service;

/**
 * Facturation du poste de santé.
 *
 * <p>Toute la mécanique vient de {@link ServiceFacturation}, contrôle d'éligibilité et
 * pièces exigées compris — le centre de santé facture de la même façon depuis l'Espace PS.
 * Le poste ne s'en distingue que par son périmètre, sa propre structure, et par le circuit
 * de transmission, qui se déduit du type de la structure émettrice.</p>
 */
@Service
public class ServiceFacturePoste extends ServiceFacturation {

    public ServiceFacturePoste(DepotFactureStructure depot,
                               ReglesFacturation regles,
                               ClientIdentite clientIdentite,
                               ClientReferentiel clientReferentiel,
                               ClientPharmacie clientPharmacie,
                               Numerotation numerotation,
                               GenerateurPdf generateurPdf,
                               DepotLettreGarantie depotLettreGarantie,
                               DepotFeuilleSoins depotFeuilleSoins,
                               DepotPieceDossier depotPieces,
                               ServiceCarteCsu serviceCarteCsu) {
        super(depot, regles, clientIdentite, clientReferentiel, clientPharmacie, numerotation, generateurPdf,
                depotLettreGarantie, depotFeuilleSoins, depotPieces, serviceCarteCsu);
    }
}
