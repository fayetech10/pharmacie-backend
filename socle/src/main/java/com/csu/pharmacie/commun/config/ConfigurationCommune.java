package com.csu.pharmacie.commun.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;
import com.csu.pharmacie.commun.client.AppelInterServices;
import com.csu.pharmacie.commun.client.ClientIdentite;
import com.csu.pharmacie.commun.client.ClientPharmacie;

import com.csu.pharmacie.commun.client.ClientReferentiel;
import com.csu.pharmacie.commun.carte.ServiceCarteCsu;
import com.csu.pharmacie.commun.facturation.DepotFactureStructure;
import com.csu.pharmacie.commun.export.GenerateurExcel;
import com.csu.pharmacie.commun.export.GenerateurPdf;
import com.csu.pharmacie.commun.export.GenerateurPdfEnfantsDistrict;
import com.csu.pharmacie.commun.facturation.CompteurTransactionnel;
import com.csu.pharmacie.commun.facturation.DepotCompteurSequence;
import com.csu.pharmacie.commun.facturation.Numerotation;
import com.csu.pharmacie.commun.facturation.NumerotationJpa;
import com.csu.pharmacie.commun.facturation.ParametresFacturation;
import com.csu.pharmacie.commun.facturation.ReglesFacturation;
import com.csu.pharmacie.commun.securite.FiltreJwt;
import com.csu.pharmacie.commun.securite.JetonService;
import com.csu.pharmacie.commun.web.ControleurSante;
import com.csu.pharmacie.commun.web.GestionnaireErreurs;

/**
 * Auto-configuration du socle : chaque microservice n'a qu'à dépendre de {@code commun}
 * pour disposer de la sécurité JWT, des règles de facturation, des générateurs de
 * documents et des clients inter-services.
 *
 * <p>Tous les beans sont {@code @ConditionalOnMissingBean} : un service qui a besoin
 * d'un comportement particulier redéfinit simplement le bean concerné.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({UrlsServices.class, ParametresFacturation.class})
public class ConfigurationCommune {

    // ────────────────────────────── sécurité ──────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public JetonService jetonService() {
        return new JetonService();
    }

    @Bean
    @ConditionalOnMissingBean
    public FiltreJwt filtreJwt(JetonService jetonService) {
        return new FiltreJwt(jetonService);
    }

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public GestionnaireErreurs gestionnaireErreurs() {
        return new GestionnaireErreurs();
    }

    @Bean
    @ConditionalOnMissingBean
    public ControleurSante controleurSante() {
        return new ControleurSante();
    }

    // ────────────────────────────── métier ──────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public ReglesFacturation reglesFacturation(ParametresFacturation parametres) {
        return new ReglesFacturation(parametres);
    }

    @Bean
    @ConditionalOnMissingBean
    public GenerateurPdf generateurPdf() {
        return new GenerateurPdf();
    }

    @Bean
    @ConditionalOnMissingBean
    public GenerateurPdfEnfantsDistrict generateurPdfEnfantsDistrict() {
        return new GenerateurPdfEnfantsDistrict();
    }

    @Bean
    @ConditionalOnMissingBean
    public GenerateurExcel generateurExcel() {
        return new GenerateurExcel();
    }

    /**
     * Numérotation persistante — n'existe que dans les services qui facturent, c'est-à-dire
     * ceux qui exposent le dépôt de compteurs (voir leur {@code @EnableJpaRepositories}).
     */
    @Bean
    @ConditionalOnBean(DepotCompteurSequence.class)
    @ConditionalOnMissingBean
    public CompteurTransactionnel compteurTransactionnel(DepotCompteurSequence depot) {
        return new CompteurTransactionnel(depot);
    }

    @Bean
    @ConditionalOnBean(CompteurTransactionnel.class)
    @ConditionalOnMissingBean(Numerotation.class)
    public Numerotation numerotation(CompteurTransactionnel compteur) {
        return new NumerotationJpa(compteur);
    }

    /**
     * Présent uniquement si le service utilise JPA — les services sans base (passerelle)
     * n'ont pas Hibernate au classpath.
     */
    @Bean
    @ConditionalOnClass(name = "org.hibernate.cfg.AvailableSettings")
    @ConditionalOnMissingBean
    public ConfigurationJsonHibernate configurationJsonHibernate(ObjectMapper objectMapper) {
        return new ConfigurationJsonHibernate(objectMapper);
    }

    /**
     * Contrôle d'éligibilité sur carte.sencsu.sn — dans les services qui facturent (poste et
     * centre de santé), qui le refont à l'enregistrement de chaque patient.
     */
    @Bean
    @ConditionalOnBean(DepotFactureStructure.class)
    @ConditionalOnMissingBean
    public ServiceCarteCsu serviceCarteCsu(@Value("${app.carte-csu.url:https://carte.sencsu.sn}") String url,
                                           @Value("${app.carte-csu.delai-connexion-ms:5000}") int delaiConnexion,
                                           @Value("${app.carte-csu.delai-lecture-ms:10000}") int delaiLecture,
                                           @Value("${app.carte-csu.periode-observation-mois:1}") int periodeObservationMois) {
        return new ServiceCarteCsu(url, delaiConnexion, delaiLecture, periodeObservationMois);
    }

    // ────────────────────────────── clients inter-services ──────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public ClientIdentite clientIdentite(RestClient.Builder builder, UrlsServices urls) {
        return new ClientIdentite(AppelInterServices.client(builder, urls.getIdentiteUrl()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientReferentiel clientReferentiel(RestClient.Builder builder, UrlsServices urls) {
        return new ClientReferentiel(AppelInterServices.client(builder, urls.getReferentielUrl()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientPharmacie clientPharmacie(RestClient.Builder builder, UrlsServices urls) {
        return new ClientPharmacie(AppelInterServices.client(builder, urls.getPharmacieUrl()));
    }

}
