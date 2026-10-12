package com.csu.pharmacie.centre.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.horscsu.DepotFactureHorsCsu;
import com.csu.pharmacie.commun.horscsu.ServiceHorsCsu;
import org.springframework.stereotype.Service;

/** Module Hors CSU du centre de santé (§8). */
@Service
public class ServiceHorsCsuCentre extends ServiceHorsCsu {

    public ServiceHorsCsuCentre(DepotFactureHorsCsu depot, ClientIdentite clientIdentite,
                                Numerotation numerotation, GenerateurPdf generateurPdf) {
        super(depot, clientIdentite, numerotation, generateurPdf);
    }
}
