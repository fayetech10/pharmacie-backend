package com.csu.pharmacie.officine.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.horscsu.DepotFactureHorsCsu;
import com.csu.pharmacie.commun.horscsu.ServiceHorsCsu;
import org.springframework.stereotype.Service;

/**
 * Caisse de la pharmacie (§14 : intégration du module Hors CSU avec la caisse).
 *
 * <p>Réutilise tel quel le socle Hors CSU des points de soins : mêmes situations
 * journalière et mensuelle, même reçu. Une officine et un poste encaissent de la même
 * façon, il n'y avait aucune raison d'en écrire deux versions.</p>
 */
@Service
public class ServiceCaisse extends ServiceHorsCsu {

    public ServiceCaisse(DepotFactureHorsCsu depot, ClientIdentite clientIdentite,
                         Numerotation numerotation, GenerateurPdf generateurPdf) {
        super(depot, clientIdentite, numerotation, generateurPdf);
    }

    @Override
    protected String prefixeNumero() {
        return "CA";
    }

    @Override
    protected String titreRecu() {
        return "Ticket de caisse";
    }
}
