package eu.pb4.enderscapepatch.impl.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A block model's representative colour in CIELAB: the mean over its face textures, weighted by face
 * area and by how much of each face a player sees of a block (mostly the top and the sides), plus
 * CIEDE2000. Kept in step with polymer-patch-bundle's copy, so both forks rank colours alike.
 */
public final class CarrierColours {
    public interface Assets {
        @Nullable InputStream open(String namespace, String path) throws IOException;
    }

    public record Variant(String model, int x, int y, int weight) {
    }

    private static final String[] DIRS = {"up", "down", "north", "south", "east", "west"};
    private static final double[] FACE_WEIGHT = {0.30, 0.06, 0.16, 0.16, 0.16, 0.16};
    private static final double[] GRASS_TINT = {0x91 / 255.0, 0xBD / 255.0, 0x59 / 255.0};

    private final Assets assets;
    private final Map<String, Optional<JsonObject>> models = new HashMap<>();
    private final Map<String, Optional<double[]>> textures = new HashMap<>();

    public CarrierColours(Assets assets) {
        this.assets = assets;
        ImageIO.setUseCache(false);
    }

    public void define(String id, JsonObject model) {
        this.models.put(normalize(id), Optional.of(model));
    }

    public @Nullable double[] lab(List<Variant> variants) {
        double[] acc = new double[3];
        double total = 0;
        for (Variant variant : variants) {
            double[][] faces = faces(variant.model());
            if (faces == null) {
                continue;
            }
            double[] col = new double[3];
            double weights = 0;
            for (int d = 0; d < 6; d++) {
                if (faces[d] == null) {
                    continue;
                }
                double w = FACE_WEIGHT[indexOf(rotate(DIRS[d], variant.x(), variant.y()))];
                for (int i = 0; i < 3; i++) {
                    col[i] += w * faces[d][i];
                }
                weights += w;
            }
            for (int i = 0; i < 3; i++) {
                acc[i] += variant.weight() * col[i] / weights;
            }
            total += variant.weight();
        }
        return total > 0 ? new double[]{acc[0] / total, acc[1] / total, acc[2] / total} : null;
    }

    private double @Nullable [][] faces(String model) {
        var textureMap = new HashMap<String, JsonElement>();
        JsonObject elementsOwner = null;
        String current = model;
        for (int depth = 0; current != null && depth < 20; depth++) {
            JsonObject json = model(current);
            if (json == null) {
                break;
            }
            if (json.has("textures")) {
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    textureMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            if (elementsOwner == null && json.has("elements")) {
                elementsOwner = json;
            }
            current = json.has("parent") ? json.get("parent").getAsString() : null;
        }

        double[][] faces = new double[6][];
        if (elementsOwner == null) {
            double[] sum = new double[3];
            int count = 0;
            for (String key : textureMap.keySet()) {
                double[] stats = texture(resolveTexture(textureMap, "#" + key), null, false);
                if (stats != null) {
                    for (int i = 0; i < 3; i++) {
                        sum[i] += stats[i];
                    }
                    count++;
                }
            }
            if (count == 0) {
                return null;
            }
            for (int d = 0; d < 6; d++) {
                faces[d] = new double[]{sum[0] / count, sum[1] / count, sum[2] / count};
            }
            return faces;
        }

        var layers = new ArrayList<List<double[]>>();
        for (int d = 0; d < 6; d++) {
            layers.add(new ArrayList<>());
        }
        for (var elementJson : elementsOwner.getAsJsonArray("elements")) {
            JsonObject element = elementJson.getAsJsonObject();
            double[] from = vec(element, "from", 0);
            double[] to = vec(element, "to", 16);
            if (!element.has("faces")) {
                continue;
            }
            for (var faceEntry : element.getAsJsonObject("faces").entrySet()) {
                int d = indexOf(faceEntry.getKey());
                if (d < 0) {
                    continue;
                }
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                String ref = resolveTexture(textureMap, face.has("texture") ? face.get("texture") : null);
                if (ref == null || ref.startsWith("#")) {
                    continue;
                }
                double[] uv = face.has("uv") ? vec(face, "uv", 0) : null;
                double[] stats = texture(ref, uv, face.has("tintindex"));
                if (stats == null) {
                    continue;
                }
                double dx = Math.abs(to[0] - from[0]), dy = Math.abs(to[1] - from[1]), dz = Math.abs(to[2] - from[2]);
                double area = d < 2 ? dx * dz : d < 4 ? dx * dy : dz * dy;
                layers.get(d).add(new double[]{from[0], from[1], from[2], to[0], to[1], to[2], area, stats[0], stats[1], stats[2], stats[3]});
            }
        }
        boolean any = false;
        for (int d = 0; d < 6; d++) {
            // Elements sharing a box are layers, composited by alpha; other boxes are weighted by area
            var groups = new ArrayList<double[]>();
            for (double[] layer : layers.get(d)) {
                double[] match = null;
                for (double[] group : groups) {
                    boolean same = true;
                    for (int i = 0; i < 6; i++) {
                        same &= Math.abs(group[i] - layer[i]) < 0.6;
                    }
                    if (same) {
                        match = group;
                        break;
                    }
                }
                if (match == null) {
                    groups.add(new double[]{layer[0], layer[1], layer[2], layer[3], layer[4], layer[5], layer[6], layer[7], layer[8], layer[9]});
                } else {
                    double alpha = layer[10];
                    for (int i = 0; i < 3; i++) {
                        match[7 + i] = alpha < 0.99 ? alpha * layer[7 + i] + (1 - alpha) * match[7 + i] : layer[7 + i];
                    }
                }
            }
            if (groups.isEmpty()) {
                continue;
            }
            double total = 0;
            double[] col = new double[3];
            for (double[] group : groups) {
                total += group[6];
                for (int i = 0; i < 3; i++) {
                    col[i] += group[6] * group[7 + i];
                }
            }
            if (total == 0) {
                total = 1;
            }
            faces[d] = new double[]{col[0] / total, col[1] / total, col[2] / total};
            any = true;
        }
        return any ? faces : null;
    }

    private @Nullable JsonObject model(String id) {
        return this.models.computeIfAbsent(normalize(id), key -> {
            try (InputStream in = open(key, "models", ".json")) {
                return in == null ? Optional.empty() : Optional.of(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // {L, a, b, mean alpha} over the first animation frame (or the face's uv area), opaque pixels only
    private double @Nullable [] texture(@Nullable String id, double @Nullable [] uv, boolean tinted) {
        if (id == null) {
            return null;
        }
        String key = normalize(id) + "|" + (uv == null ? "" : uv[0] + "," + uv[1] + "," + uv[2] + "," + uv[3]) + "|" + tinted;
        return this.textures.computeIfAbsent(key, k -> {
            try (InputStream in = open(normalize(id), "textures", ".png")) {
                if (in == null) {
                    return Optional.empty();
                }
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    return Optional.empty();
                }
                int w = image.getWidth(), h = Math.min(image.getHeight(), w);
                int x0 = 0, y0 = 0, x1 = w, y1 = h;
                if (uv != null) {
                    double ux0 = Math.min(uv[0], uv[2]) * w / 16, ux1 = Math.max(uv[0], uv[2]) * w / 16;
                    double vy0 = Math.min(uv[1], uv[3]) * h / 16, vy1 = Math.max(uv[1], uv[3]) * h / 16;
                    if (ux1 - ux0 >= 1 && vy1 - vy0 >= 1) {
                        x0 = (int) ux0;
                        y0 = (int) vy0;
                        x1 = Math.min(w, (int) Math.ceil(ux1));
                        y1 = Math.min(h, (int) Math.ceil(vy1));
                    }
                }
                double[] sum = new double[3];
                double alphaSum = 0;
                int opaque = 0, pixels = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int argb = image.getRGB(x, y);
                        double alpha = (argb >>> 24) / 255.0;
                        alphaSum += alpha;
                        pixels++;
                        if (alpha <= 0.1) {
                            continue;
                        }
                        double r = ((argb >> 16) & 0xFF) / 255.0, g = ((argb >> 8) & 0xFF) / 255.0, b = (argb & 0xFF) / 255.0;
                        if (tinted) {
                            r *= GRASS_TINT[0];
                            g *= GRASS_TINT[1];
                            b *= GRASS_TINT[2];
                        }
                        double[] lab = srgbToLab(r, g, b);
                        for (int i = 0; i < 3; i++) {
                            sum[i] += lab[i];
                        }
                        opaque++;
                    }
                }
                if (opaque == 0) {
                    return Optional.empty();
                }
                return Optional.of(new double[]{sum[0] / opaque, sum[1] / opaque, sum[2] / opaque, alphaSum / pixels});
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private @Nullable InputStream open(String id, String kind, String extension) throws IOException {
        int colon = id.indexOf(':');
        return this.assets.open(id.substring(0, colon), kind + "/" + id.substring(colon + 1) + extension);
    }

    private static @Nullable String resolveTexture(Map<String, JsonElement> textures, @Nullable Object ref) {
        String value = textureName(ref);
        for (int i = 0; value != null && value.startsWith("#") && i < 10; i++) {
            value = textureName(textures.get(value.substring(1)));
        }
        return value;
    }

    private static @Nullable String textureName(@Nullable Object ref) {
        if (ref instanceof String string) {
            return string;
        }
        if (ref instanceof JsonElement element) {
            if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
            if (element.isJsonObject() && element.getAsJsonObject().has("sprite")) {
                return element.getAsJsonObject().get("sprite").getAsString();
            }
        }
        return null;
    }

    private static String normalize(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static double[] vec(JsonObject json, String key, double fallback) {
        if (!json.has(key)) {
            return new double[]{fallback, fallback, fallback};
        }
        var array = json.getAsJsonArray(key);
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    private static int indexOf(String dir) {
        for (int i = 0; i < 6; i++) {
            if (DIRS[i].equals(dir)) {
                return i;
            }
        }
        return -1;
    }

    // Blockstate rotation: x about the X axis, then y about the Y axis
    private static String rotate(String dir, int x, int y) {
        for (int i = 0; i < ((x / 90) % 4 + 4) % 4; i++) {
            dir = switch (dir) {
                case "up" -> "north";
                case "north" -> "down";
                case "down" -> "south";
                case "south" -> "up";
                default -> dir;
            };
        }
        for (int i = 0; i < ((y / 90) % 4 + 4) % 4; i++) {
            dir = switch (dir) {
                case "north" -> "east";
                case "east" -> "south";
                case "south" -> "west";
                case "west" -> "north";
                default -> dir;
            };
        }
        return dir;
    }

    private static double[] srgbToLab(double r, double g, double b) {
        double lr = linear(r), lg = linear(g), lb = linear(b);
        double x = (0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb) / 0.95047;
        double y = 0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb;
        double z = (0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb) / 1.08883;
        double fx = labF(x), fy = labF(y), fz = labF(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    public static double ciede2000(double[] lab1, double[] lab2) {
        double l1 = lab1[0], a1 = lab1[1], b1 = lab1[2], l2 = lab2[0], a2 = lab2[1], b2 = lab2[2];
        double cm = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2;
        double g = 0.5 * (1 - Math.sqrt(Math.pow(cm, 7) / (Math.pow(cm, 7) + Math.pow(25, 7))));
        double a1p = (1 + g) * a1, a2p = (1 + g) * a2;
        double c1p = Math.hypot(a1p, b1), c2p = Math.hypot(a2p, b2);
        double h1p = c1p == 0 ? 0 : (Math.toDegrees(Math.atan2(b1, a1p)) + 360) % 360;
        double h2p = c2p == 0 ? 0 : (Math.toDegrees(Math.atan2(b2, a2p)) + 360) % 360;
        double dlp = l2 - l1, dcp = c2p - c1p;
        double dhp;
        if (c1p * c2p == 0) {
            dhp = 0;
        } else if (Math.abs(h2p - h1p) <= 180) {
            dhp = h2p - h1p;
        } else if (h2p - h1p > 180) {
            dhp = h2p - h1p - 360;
        } else {
            dhp = h2p - h1p + 360;
        }
        double dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp / 2));
        double lpm = (l1 + l2) / 2, cpm = (c1p + c2p) / 2;
        double hpm;
        if (c1p * c2p == 0) {
            hpm = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= 180) {
            hpm = (h1p + h2p) / 2;
        } else if (h1p + h2p < 360) {
            hpm = (h1p + h2p + 360) / 2;
        } else {
            hpm = (h1p + h2p - 360) / 2;
        }
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hpm - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hpm))
                + 0.32 * Math.cos(Math.toRadians(3 * hpm + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hpm - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hpm - 275) / 25, 2));
        double rc = 2 * Math.sqrt(Math.pow(cpm, 7) / (Math.pow(cpm, 7) + Math.pow(25, 7)));
        double sl = 1 + 0.015 * Math.pow(lpm - 50, 2) / Math.sqrt(20 + Math.pow(lpm - 50, 2));
        double sc = 1 + 0.045 * cpm, sh = 1 + 0.015 * cpm * t;
        double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;
        return Math.sqrt(Math.pow(dlp / sl, 2) + Math.pow(dcp / sc, 2) + Math.pow(dHp / sh, 2) + rt * (dcp / sc) * (dHp / sh));
    }
}
