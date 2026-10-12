package com.csu.pharmacie.scheduler;

import com.csu.pharmacie.service.FactureStructureService;
import com.csu.pharmacie.service.FactureStructureService.ClotureResultat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Clôture mensuelle automatique des factures de structure (circuit CSU).
 *
 * <p>Plutôt que de laisser l'agent cliquer « Envoyer », le serveur transmet lui-même les
 * factures du mois écoulé, une fois passé le <b>délai de grâce</b> qui laisse le temps de
 * saisir les patients vus en fin de mois (jour configurable, par défaut le 5 du mois
 * suivant). La logique métier — qui part, vers quel échelon, quoi laisser en attente — est
 * dans {@link FactureStructureService#cloturerPeriodeAutomatique(int, int)}.</p>
 *
 * <p>Le job tourne ici, dans l'application centrale (instance unique partageant la base),
 * pour ne jamais s'exécuter en double. Le VPS héberge un processus permanent, donc le
 * déclenchement quotidien est fiable ; l'opération est en outre idempotente, si bien qu'une
 * exécution manquée (serveur arrêté au moment du passage) est rattrapée dès le lendemain.</p>
 */
@Component
public class ClotureMensuelleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClotureMensuelleScheduler.class);
    private static final ZoneId FUSEAU = ZoneId.of("Africa/Dakar");

    private final FactureStructureService factureService;

    /** Interrupteur général (permet de couper l'automatisme sans redéployer). */
    @Value("${app.facturation.cloture.auto:true}")
    private boolean actif;

    /** Jour du mois à partir duquel le mois précédent est clôturé (délai de grâce). */
    @Value("${app.facturation.cloture.jour:5}")
    private int jourCloture;

    /**
     * Garde-fou de démarrage : premier mois (format {@code AAAA-MM}) pouvant être clôturé
     * automatiquement. Les mois antérieurs ne partent jamais tout seuls — ils évitent une
     * transmission massive rétroactive le jour de la mise en service. Vide = aucun plancher.
     */
    @Value("${app.facturation.cloture.a-partir-de:}")
    private String aPartirDe;

    public ClotureMensuelleScheduler(FactureStructureService factureService) {
        this.factureService = factureService;
    }

    /**
     * Chaque jour à 23 h (heure de Dakar) : à partir du jour de clôture, transmet les
     * brouillons complets du mois précédent. Avant ce jour, on ne touche à rien — le mois
     * précédent est encore dans son délai de grâce.
     */
    @Scheduled(cron = "${app.facturation.cloture.cron:0 0 23 * * *}", zone = "Africa/Dakar")
    public void executer() {
        if (!actif) {
            return;
        }
        LocalDate aujourdhui = LocalDate.now(FUSEAU);
        if (aujourdhui.getDayOfMonth() < jourCloture) {
            return;
        }
        LocalDate moisAClore = aujourdhui.minusMonths(1);
        YearMonth plancher = plancher();
        if (plancher != null && YearMonth.from(moisAClore).isBefore(plancher)) {
            // Garde-fou : ne jamais transmettre rétroactivement les mois d'avant la mise en service.
            return;
        }
        try {
            ClotureResultat r = factureService.cloturerPeriodeAutomatique(
                    moisAClore.getMonthValue(), moisAClore.getYear());
            if (r.envoyees() > 0 || r.incompletes() > 0 || r.parLaStructure() > 0) {
                log.info("Clôture mensuelle {}/{} : {} facture(s) transmise(s), "
                                + "{} laissée(s) en attente (dossiers à compléter), {} vide(s) ignorée(s), "
                                + "{} de centres / EPS laissée(s) à leur structure.",
                        moisAClore.getMonthValue(), moisAClore.getYear(),
                        r.envoyees(), r.incompletes(), r.vides(), r.parLaStructure());
            }
        } catch (Exception e) {
            // Un échec ne doit pas tuer le planificateur : on réessaiera au prochain passage.
            log.error("Clôture mensuelle {}/{} en échec : {}",
                    moisAClore.getMonthValue(), moisAClore.getYear(), e.getMessage(), e);
        }
    }

    /** Plancher « AAAA-MM » ou {@code null} (vide, ou valeur illisible — alors on prévient). */
    private YearMonth plancher() {
        if (aPartirDe == null || aPartirDe.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(aPartirDe.trim());
        } catch (DateTimeParseException e) {
            log.warn("app.facturation.cloture.a-partir-de illisible (« {} », attendu AAAA-MM) : "
                    + "garde-fou de démarrage ignoré.", aPartirDe);
            return null;
        }
    }
}
