package bigtrade;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.HistoricalDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.IntervalListener;
import velox.api.layer1.simplified.Intervals;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Big Trade : affiche sur la heatmap une bulle pour chaque ordre agressif
 * (market order) dont la taille cumulée dépasse un seuil.
 *
 * - Bulle verte  : acheteur agressif (le marché a "payé" l'ask)
 * - Bulle rouge  : vendeur agressif (le marché a "tapé" le bid)
 * - Taille de la bulle proportionnelle à la taille de l'exécution
 *
 * Un market order qui balaie plusieurs niveaux arrive dans Bookmap en
 * plusieurs onTrade. On les regroupe en une seule exécution :
 *   1. via TradeInfo.isExecutionStart / isExecutionEnd (si la source les fournit),
 *   2. sinon / en complément : même côté + écart de temps inférieur à la fenêtre.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Big Trade")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
public class BigTradeIndicator implements CustomModule, TradeDataListener, TimeListener,
        IntervalListener, HistoricalDataListener {

    // ---------------------------------------------------------------- paramètres

    @Parameter(name = "Taille minimale (contrats)", minimum = 1, maximum = 1000000, step = 1)
    private Integer minSize = 50;

    @Parameter(name = "Seuil adaptatif : multiple de la moyenne (0 = off)", minimum = 0, maximum = 100, step = 0.5)
    private Double adaptiveMultiplier = 0.0;

    @Parameter(name = "Fenetre de regroupement (ms)", minimum = 1, maximum = 5000, step = 1)
    private Integer mergeWindowMs = 50;

    @Parameter(name = "Utiliser les flags debut/fin d'execution")
    private Boolean useExecutionFlags = true;

    @Parameter(name = "Rayon mini (px)", minimum = 4, maximum = 60, step = 1)
    private Integer minRadius = 8;

    @Parameter(name = "Rayon maxi (px)", minimum = 8, maximum = 120, step = 1)
    private Integer maxRadius = 40;

    @Parameter(name = "Afficher la taille dans la bulle")
    private Boolean showLabel = true;

    @Parameter(name = "Couleur acheteur agressif")
    private Color buyColor = new Color(0, 200, 90);

    @Parameter(name = "Couleur vendeur agressif")
    private Color sellColor = new Color(235, 60, 60);

    // ------------------------------------------------------------------- état

    private static final double EMA_ALPHA = 0.02;
    private static final int ADAPTIVE_WARMUP = 30;
    private static final Color INVISIBLE = new Color(0, 0, 0, 0);

    private Indicator buyIndicator;
    private Indicator sellIndicator;

    /** Dernier timestamp reçu, en nanosecondes (vrai timestamp de tous les évènements suivants). */
    private long now;

    // exécution en cours d'agrégation
    private boolean hasPending;
    private boolean pendingBid;
    private long pendingSize;
    private double pendingNotional;
    private long pendingLastTs;

    // moyenne mobile des tailles d'exécution (seuil adaptatif)
    private double avgExecSize;
    private long execCount;

    // ------------------------------------------------------------- cycle de vie

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        buyIndicator = api.registerIndicator("Big Trade - achats", GraphType.PRIMARY);
        sellIndicator = api.registerIndicator("Big Trade - ventes", GraphType.PRIMARY);
        // La ligne est invisible : seules les icônes (bulles) sont affichées.
        buyIndicator.setColor(INVISIBLE);
        sellIndicator.setColor(INVISIBLE);
    }

    @Override
    public void stop() {
        // rien à libérer
    }

    // ---------------------------------------------------------------- données

    @Override
    public void onTimestamp(long nanoseconds) {
        now = nanoseconds;
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        boolean bid = tradeInfo.isBidAggressor;
        boolean flags = useExecutionFlags;

        if (hasPending) {
            boolean sideChanged = bid != pendingBid;
            boolean gap = now - pendingLastTs > windowNs();
            boolean newExecution = flags && tradeInfo.isExecutionStart;
            if (sideChanged || gap || newExecution) {
                flush();
            }
        }

        if (!hasPending) {
            hasPending = true;
            pendingBid = bid;
            pendingSize = 0;
            pendingNotional = 0;
        }
        pendingSize += size;
        pendingNotional += price * size;
        pendingLastTs = now;

        if (flags && tradeInfo.isExecutionEnd) {
            flush();
        }
    }

    // Termine une exécution restée en attente quand plus aucun trade n'arrive.
    @Override
    public long getInterval() {
        return Intervals.INTERVAL_100_MILLISECONDS;
    }

    @Override
    public void onInterval() {
        if (hasPending && now - pendingLastTs > windowNs()) {
            flush();
        }
    }

    // --------------------------------------------------------------- logique

    private long windowNs() {
        return mergeWindowMs * 1_000_000L;
    }

    private double threshold() {
        double t = minSize;
        if (adaptiveMultiplier > 0 && execCount >= ADAPTIVE_WARMUP) {
            t = Math.max(t, adaptiveMultiplier * avgExecSize);
        }
        return t;
    }

    private void flush() {
        if (!hasPending) {
            return;
        }
        hasPending = false;

        long size = pendingSize;
        double threshold = threshold();
        updateAverage(size);

        if (size < threshold) {
            return;
        }
        // Prix de la bulle = VWAP de l'exécution, arrondi au niveau de prix (tick) le plus proche.
        double price = Math.round(pendingNotional / size);
        emit(pendingBid, price, size, threshold);
    }

    private void updateAverage(long size) {
        if (execCount == 0) {
            avgExecSize = size;
        } else {
            avgExecSize += EMA_ALPHA * (size - avgExecSize);
        }
        execCount++;
    }

    private void emit(boolean buyAggressor, double price, long size, double threshold) {
        Indicator indicator = buyAggressor ? buyIndicator : sellIndicator;
        BufferedImage icon = makeIcon(buyAggressor, size, threshold);
        indicator.addPoint(price);
        indicator.addIcon(price, icon, icon.getWidth() / 2, icon.getHeight() / 2);
    }

    // ----------------------------------------------------------------- rendu

    private BufferedImage makeIcon(boolean buyAggressor, long size, double threshold) {
        int rMin = Math.max(2, minRadius);
        int rMax = Math.max(rMin, maxRadius);

        // Surface proportionnelle à la taille : rayon ~ sqrt(size / seuil).
        double ratio = Math.sqrt(size / Math.max(1.0, threshold));
        int radius = (int) Math.round(Math.min(rMax, Math.max(rMin, rMin * ratio)));

        int dim = radius * 2 + 4;
        BufferedImage img = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color base = buyAggressor ? buyColor : sellColor;
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 150));
            g.fillOval(2, 2, radius * 2, radius * 2);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 255));
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(2, 2, radius * 2, radius * 2);

            if (showLabel && radius >= 11) {
                String text = formatSize(size);
                int fontSize = Math.min(16, Math.max(9, radius * 2 / 3));
                Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                g.setFont(font);
                FontMetrics fm = g.getFontMetrics();
                while (fm.stringWidth(text) > radius * 2 - 2 && fontSize > 7) {
                    fontSize--;
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
                    fm = g.getFontMetrics();
                }
                int x = (dim - fm.stringWidth(text)) / 2;
                int y = (dim - fm.getHeight()) / 2 + fm.getAscent();
                g.setColor(Color.WHITE);
                g.drawString(text, x, y);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    static String formatSize(long size) {
        if (size >= 10_000) {
            return (size / 1000) + "k";
        }
        if (size >= 1_000) {
            return String.format("%.1fk", size / 1000.0);
        }
        return Long.toString(size);
    }
}
