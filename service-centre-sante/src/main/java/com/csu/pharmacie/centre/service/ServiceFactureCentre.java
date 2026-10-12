package com.csu.pharmacie.centre.service;

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
 * Facturation propre du centre de santé / EPS.
 *
 * <p>Même socle que le poste, à une différence près portée par la classe de base : un
 * centre transmet directement au service régional, sans étape intermédiaire — c'est lui
 * qui joue ce rôle pour les postes qu'il polarise.</p>
 *
 * <p>Deux façons de saisir un patient : sur lettre de garantie (circuit BCSU, la lettre
 * fait foi), ou directement depuis l'Espace PS, comme un poste — code d'immatriculation
 * contrôlé par le serveur, pièces exigées selon le régime.</p>
 */
@Service
public class ServiceFactureCentre extends ServiceFacturation {

    public ServiceFactureCentre(DepotFactureStructure depot,
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
