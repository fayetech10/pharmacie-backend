package com.csu.pharmacie.referentiel.controleur;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.csu.pharmacie.commun.client.dto.PrestationDto;
import com.csu.pharmacie.referentiel.service.ServicePrestation;

import java.util.List;

/**
 * Tarification en lot pour les services de facturation.
 *
 * <p>C'est le point d'entrée qui rend impossible la facturation d'un prix arbitraire :
 * les services poste et centre envoient les identifiants des prestations d'une facture et
 * réappliquent les prix reçus ici, en ignorant ceux transmis par le navigateur.</p>
 */
@RestController
@RequestMapping("/interne/prestations")
@RequiredArgsConstructor
public class ControleurInterneReferentiel {

    private final ServicePrestation servicePrestation;

    @PostMapping("/tarifs")
    public List<PrestationDto> tarifs(@RequestBody List<String> prestationIds) {
        return servicePrestation.tarifs(prestationIds);
    }
}
