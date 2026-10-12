package com.csu.pharmacie.poste.service;

import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.horscsu.DepotFactureHorsCsu;
import com.csu.pharmacie.commun.horscsu.ServiceHorsCsu;
import org.springframework.stereotype.Service;

/** Module Hors CSU du poste de santé (§8) : caisse, situations et réimpression. */
@Service
public class ServiceHorsCsuPoste extends ServiceHorsCsu {

    public ServiceHorsCsuPoste(DepotFactureHorsCsu depot, ClientIdentite clientIdentite,
                               Numerotation numerotation, GenerateurPdf generateurPdf) {
        super(depot, clientIdentite, numerotation, generateurPdf);
    }

    @Override
    protected String prefixeNumero() {
        return "HP";
    }
}
