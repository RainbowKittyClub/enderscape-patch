package eu.pb4.enderscapepatch.impl.block;

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
import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

// A block model's colour in CIELAB, the mean of its face textures weighted by face area and by how
// much of each face a player sees, and CIEDE2000 between two colours.
final class CarrierColours {

    // Opens assets/<namespace>/<path> (path such as models/block/stone.json), or null when missing.
    @FunctionalInterface
    interface Assets {

        // The asset's bytes, or null.
        @Nullable InputStream open(String namespace, String path) throws IOException;
    }

    // One model of a variant list or multipart blockstate: id, rotation in degrees, weight.
    record Variant(String model, int x, int y, int weight) {}

    // Face order shared by every per-direction array here.
    private static final String[] DIRS = {"up", "down", "north", "south", "east", "west"};
    // How much of each face a player sees: the top and the four sides, the bottom barely.
    private static final double[] FACE_WEIGHT = {0.30, 0.06, 0.16, 0.16, 0.16, 0.16};
    // Plains grass, for faces the client tints.
    private static final double[] GRASS_TINT = {0x91 / 255.0, 0xBD / 255.0, 0x59 / 255.0};

    // Where models and textures come from.
    private final Assets assets;
    // Model JSON by id; empty when missing.
    private final Map<String, Optional<JsonObject>> models = new HashMap<>();
    // A blockstate's variants by block id; empty when missing or multipart.
    private final Map<String, Optional<JsonObject>> blockstates = new HashMap<>();
    // Per-direction colours by model id; empty when the model has no texture.
    private final Map<String, Optional<double[][]>> faces = new HashMap<>();
    // Decoded textures by id; empty when missing or unreadable.
    private final Map<String, Optional<BufferedImage>> images = new HashMap<>();
    // {L, a, b, mean alpha} by texture id, uv and tint; empty when missing.
    private final Map<String, Optional<double[]>> textures = new HashMap<>();

    // A colour source reading models, blockstates and textures through assets.
    CarrierColours(Assets assets) {
        this.assets = assets;
        ImageIO.setUseCache(false);
    }

    // Registers a model that only exists once the pack is built, so its colour is known before.
    void define(String id, JsonObject model) {
        this.models.put(normalize(id), Optional.of(model));
        this.faces.remove(normalize(id));
    }

    // The colour of a variant list, each model's averaged by weight; null when none has a texture.
    double @Nullable [] lab(List<Variant> variants) {
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
        return total > 0 ? new double[] {acc[0] / total, acc[1] / total, acc[2] / total} : null;
    }

    // The colour of parts drawn together, their faces merged per direction by area; null if none.
    double @Nullable [] partsLab(List<Variant> parts) {
        double[][] sums = new double[6][4];
        for (Variant part : parts) {
            Resolved resolved = resolve(part.model());
            if (resolved.elements() == null) {
                continue;
            }
            for (JsonElement elementJson : resolved.elements()) {
                JsonObject element = elementJson.getAsJsonObject();
                if (!element.has("faces")) {
                    continue;
                }
                double[] from = vec(element, "from", 0);
                double[] to = vec(element, "to", 16);
                for (var faceEntry : element.getAsJsonObject("faces").entrySet()) {
                    int d = indexOf(faceEntry.getKey());
                    double[] stats = d < 0 ? null : faceStats(resolved, faceEntry.getValue());
                    if (stats == null) {
                        continue;
                    }
                    double area = area(d, from, to);
                    double[] sum = sums[indexOf(rotate(DIRS[d], part.x(), part.y()))];
                    for (int i = 0; i < 3; i++) {
                        sum[i] += area * stats[i];
                    }
                    sum[3] += area;
                }
            }
        }
        double[] col = new double[3];
        double weights = 0;
        for (int d = 0; d < 6; d++) {
            if (sums[d][3] == 0) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                col[i] += FACE_WEIGHT[d] * sums[d][i] / sums[d][3];
            }
            weights += FACE_WEIGHT[d];
        }
        return weights > 0 ? new double[] {col[0] / weights, col[1] / weights, col[2] / weights}
                : null;
    }

    // The colour of a block's first blockstate variant whose key has every property, or null.
    double @Nullable [] blockstateLab(String block, List<String> properties) {
        JsonObject variants = this.blockstates.computeIfAbsent(block, key -> {
            try (InputStream in = open(key, "blockstates", ".json")) {
                return in == null ? Optional.empty() : Optional.ofNullable(JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonObject("variants"));
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
        if (variants == null) {
            return null;
        }
        try {
            for (var entry : variants.entrySet()) {
                if (List.of(entry.getKey().split(",")).containsAll(properties)) {
                    return lab(blockstateVariants(entry.getValue()));
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // CIEDE2000 colour difference between two CIELAB colours.
    static double ciede2000(double[] lab1, double[] lab2) {
        double l1 = lab1[0];
        double a1 = lab1[1];
        double b1 = lab1[2];
        double l2 = lab2[0];
        double a2 = lab2[1];
        double b2 = lab2[2];
        double cm = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2;
        double g = 0.5 * (1 - Math.sqrt(Math.pow(cm, 7) / (Math.pow(cm, 7) + Math.pow(25, 7))));
        double a1p = (1 + g) * a1;
        double a2p = (1 + g) * a2;
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);
        double h1p = c1p == 0 ? 0 : (Math.toDegrees(Math.atan2(b1, a1p)) + 360) % 360;
        double h2p = c2p == 0 ? 0 : (Math.toDegrees(Math.atan2(b2, a2p)) + 360) % 360;
        double dlp = l2 - l1;
        double dcp = c2p - c1p;
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
        double lpm = (l1 + l2) / 2;
        double cpm = (c1p + c2p) / 2;
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
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hpm - 30))
                + 0.24 * Math.cos(Math.toRadians(2 * hpm))
                + 0.32 * Math.cos(Math.toRadians(3 * hpm + 6))
                - 0.20 * Math.cos(Math.toRadians(4 * hpm - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hpm - 275) / 25, 2));
        double rc = 2 * Math.sqrt(Math.pow(cpm, 7) / (Math.pow(cpm, 7) + Math.pow(25, 7)));
        double sl = 1 + 0.015 * Math.pow(lpm - 50, 2) / Math.sqrt(20 + Math.pow(lpm - 50, 2));
        double sc = 1 + 0.045 * cpm;
        double sh = 1 + 0.015 * cpm * t;
        double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;
        return Math.sqrt(Math.pow(dlp / sl, 2) + Math.pow(dcp / sc, 2) + Math.pow(dHp / sh, 2)
                + rt * (dcp / sc) * (dHp / sh));
    }

    // A blockstate's variant value, one object or a weighted list.
    private static List<Variant> blockstateVariants(JsonElement value) {
        List<Variant> out = new ArrayList<>();
        for (JsonElement element : value.isJsonArray() ? value.getAsJsonArray().asList()
                : List.of(value)) {
            JsonObject variant = element.getAsJsonObject();
            out.add(new Variant(variant.get("model").getAsString(),
                    variant.has("x") ? variant.get("x").getAsInt() : 0,
                    variant.has("y") ? variant.get("y").getAsInt() : 0,
                    variant.has("weight") ? variant.get("weight").getAsInt() : 1));
        }
        return out;
    }

    // A model's textures merged along its parents, and the nearest parent's elements.
    private record Resolved(Map<String, JsonElement> textures, @Nullable JsonArray elements) {}

    // Walks the parent chain, children's textures winning, up to twenty levels.
    private Resolved resolve(String model) {
        Map<String, JsonElement> textureMap = new HashMap<>();
        JsonArray elements = null;
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
            if (elements == null && json.has("elements")) {
                elements = json.getAsJsonArray("elements");
            }
            current = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        return new Resolved(textureMap, elements);
    }

    // Per-direction colours of one model, or null when it has no texture; cached.
    private double @Nullable [][] faces(String model) {
        return this.faces.computeIfAbsent(normalize(model),
                key -> Optional.ofNullable(modelFaces(key))).orElse(null);
    }

    // Per-direction colours of one model, or null when it has no texture.
    private double @Nullable [][] modelFaces(String model) {
        Resolved resolved = resolve(model);
        double[][] faces = new double[6][];
        if (resolved.elements() == null) {
            double[] sum = new double[3];
            int count = 0;
            for (String key : resolved.textures().keySet()) {
                String ref = resolveTexture(resolved.textures(), "#" + key);
                double[] stats = texture(ref, null, false);
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
                faces[d] = new double[] {sum[0] / count, sum[1] / count, sum[2] / count};
            }
            return faces;
        }

        List<List<double[]>> layers = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            layers.add(new ArrayList<>());
        }
        for (JsonElement elementJson : resolved.elements()) {
            JsonObject element = elementJson.getAsJsonObject();
            if (!element.has("faces")) {
                continue;
            }
            double[] from = vec(element, "from", 0);
            double[] to = vec(element, "to", 16);
            for (var faceEntry : element.getAsJsonObject("faces").entrySet()) {
                int d = indexOf(faceEntry.getKey());
                double[] stats = d < 0 ? null : faceStats(resolved, faceEntry.getValue());
                if (stats == null) {
                    continue;
                }
                layers.get(d).add(new double[] {from[0], from[1], from[2], to[0], to[1], to[2],
                    area(d, from, to), stats[0], stats[1], stats[2], stats[3]});
            }
        }
        boolean any = false;
        for (int d = 0; d < 6; d++) {
            List<double[]> groups = groupLayers(layers.get(d));
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
            faces[d] = new double[] {col[0] / total, col[1] / total, col[2] / total};
            any = true;
        }
        return any ? faces : null;
    }

    // Faces sharing an element box are layers, composited by alpha; other boxes stay separate.
    private static List<double[]> groupLayers(List<double[]> layers) {
        List<double[]> groups = new ArrayList<>();
        for (double[] layer : layers) {
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
                double[] group = new double[10];
                System.arraycopy(layer, 0, group, 0, 10);
                groups.add(group);
            } else {
                double alpha = layer[10];
                for (int i = 0; i < 3; i++) {
                    match[7 + i] = alpha < 0.99
                            ? alpha * layer[7 + i] + (1 - alpha) * match[7 + i] : layer[7 + i];
                }
            }
        }
        return groups;
    }

    // {L, a, b, alpha} of one element face, or null when its texture is missing.
    private double @Nullable [] faceStats(Resolved resolved, JsonElement faceJson) {
        JsonObject face = faceJson.getAsJsonObject();
        JsonElement texture = face.has("texture") ? face.get("texture") : null;
        String ref = resolveTexture(resolved.textures(), texture);
        if (ref == null || ref.startsWith("#")) {
            return null;
        }
        return texture(ref, face.has("uv") ? vec(face, "uv", 0) : null, face.has("tintindex"));
    }

    // The face's area in pixels squared: the element's extent across the face.
    private static double area(int d, double[] from, double[] to) {
        double dx = Math.abs(to[0] - from[0]);
        double dy = Math.abs(to[1] - from[1]);
        double dz = Math.abs(to[2] - from[2]);
        return d < 2 ? dx * dz : d < 4 ? dx * dy : dz * dy;
    }

    // Model JSON by id, cached.
    private @Nullable JsonObject model(String id) {
        return this.models.computeIfAbsent(normalize(id), key -> {
            try (InputStream in = open(key, "models", ".json")) {
                return in == null ? Optional.empty() : Optional.of(JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // {L, a, b, mean alpha} over the first animation frame (or the uv area), opaque pixels only.
    private double @Nullable [] texture(@Nullable String id, double @Nullable [] uv,
            boolean tinted) {
        if (id == null) {
            return null;
        }
        double[] area = whole(uv) ? null : uv;
        String key = normalize(id) + "|" + (area == null ? ""
                : area[0] + "," + area[1] + "," + area[2] + "," + area[3]) + "|" + tinted;
        return this.textures.computeIfAbsent(key, k -> {
            try {
                BufferedImage image = image(normalize(id));
                return image == null ? Optional.empty()
                        : Optional.ofNullable(stats(image, area, tinted));
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // A texture decoded once, or null when missing or unreadable.
    private @Nullable BufferedImage image(String id) {
        return this.images.computeIfAbsent(id, key -> {
            try (InputStream in = open(key, "textures", ".png")) {
                return in == null ? Optional.empty() : Optional.ofNullable(ImageIO.read(in));
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // Whether a face uv covers the whole texture, which reads the same as no uv.
    private static boolean whole(double @Nullable [] uv) {
        return uv != null && Math.min(uv[0], uv[2]) == 0 && Math.max(uv[0], uv[2]) == 16
                && Math.min(uv[1], uv[3]) == 0 && Math.max(uv[1], uv[3]) == 16;
    }

    // Mean CIELAB of the image's opaque pixels and its mean alpha, within uv when given.
    private static double @Nullable [] stats(BufferedImage image, double @Nullable [] uv,
            boolean tinted) {
        int w = image.getWidth();
        int h = Math.min(image.getHeight(), w);
        int x0 = 0;
        int y0 = 0;
        int x1 = w;
        int y1 = h;
        if (uv != null) {
            double ux0 = Math.min(uv[0], uv[2]) * w / 16;
            double ux1 = Math.max(uv[0], uv[2]) * w / 16;
            double vy0 = Math.min(uv[1], uv[3]) * h / 16;
            double vy1 = Math.max(uv[1], uv[3]) * h / 16;
            if (ux1 - ux0 >= 1 && vy1 - vy0 >= 1) {
                x0 = (int) ux0;
                y0 = (int) vy0;
                x1 = Math.min(w, (int) Math.ceil(ux1));
                y1 = Math.min(h, (int) Math.ceil(vy1));
            }
        }
        double[] sum = new double[3];
        double alphaSum = 0;
        int opaque = 0;
        int pixels = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = image.getRGB(x, y);
                double alpha = (argb >>> 24) / 255.0;
                alphaSum += alpha;
                pixels++;
                if (alpha <= 0.1) {
                    continue;
                }
                double r = ((argb >> 16) & 0xFF) / 255.0;
                double g = ((argb >> 8) & 0xFF) / 255.0;
                double b = (argb & 0xFF) / 255.0;
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
            return null;
        }
        return new double[] {sum[0] / opaque, sum[1] / opaque, sum[2] / opaque, alphaSum / pixels};
    }

    // Opens a model or texture by its id.
    private @Nullable InputStream open(String id, String kind, String extension)
            throws IOException {
        int colon = id.indexOf(':');
        String path = kind + "/" + id.substring(colon + 1) + extension;
        return this.assets.open(id.substring(0, colon), path);
    }

    // Follows #references through the merged texture map.
    private static @Nullable String resolveTexture(Map<String, JsonElement> textures,
            @Nullable Object ref) {
        String value = textureName(ref);
        for (int i = 0; value != null && value.startsWith("#") && i < 10; i++) {
            value = textureName(textures.get(value.substring(1)));
        }
        return value;
    }

    // A texture value as a plain string, or the sprite of a texture object.
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

    // Adds the default namespace.
    private static String normalize(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    // A JSON number array, or three copies of the fallback when absent.
    private static double[] vec(JsonObject json, String key, double fallback) {
        if (!json.has(key)) {
            return new double[] {fallback, fallback, fallback};
        }
        var array = json.getAsJsonArray(key);
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    // Index of a direction name in DIRS, or -1.
    private static int indexOf(String dir) {
        for (int i = 0; i < 6; i++) {
            if (DIRS[i].equals(dir)) {
                return i;
            }
        }
        return -1;
    }

    // Blockstate rotation of a face direction: x about the X axis, then y about the Y axis.
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

    // sRGB in 0..1 to CIELAB (D65).
    private static double[] srgbToLab(double r, double g, double b) {
        double lr = linear(r);
        double lg = linear(g);
        double lb = linear(b);
        double x = (0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb) / 0.95047;
        double y = 0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb;
        double z = (0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb) / 1.08883;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    // sRGB transfer function, inverted.
    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    // CIELAB's companding function.
    private static double labF(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }
}
