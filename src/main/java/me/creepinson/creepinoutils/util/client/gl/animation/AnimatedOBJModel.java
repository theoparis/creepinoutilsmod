package me.creepinson.creepinoutils.util.client.gl.animation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import dev.throwouterror.util.math.Tensor;
import joptsimple.internal.Strings;
import me.creepinson.creepinoutils.util.TensorUtils;
import net.minecraft.client.renderer.TransformationMatrix;
import net.minecraft.client.renderer.Vector4f;
import net.minecraft.client.renderer.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraftforge.client.model.IModelBuilder;
import net.minecraftforge.client.model.IModelConfiguration;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.client.model.geometry.IModelGeometryPart;
import net.minecraftforge.client.model.geometry.IMultipartModelGeometry;
import net.minecraftforge.client.model.obj.LineReader;
import net.minecraftforge.client.model.obj.MaterialLibrary;
import net.minecraftforge.client.model.pipeline.BakedQuadBuilder;
import net.minecraftforge.client.model.pipeline.IVertexConsumer;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AnimatedOBJModel implements IMultipartModelGeometry<AnimatedOBJModel> {
    private static Tensor COLOR_WHITE = new Tensor(1, 1, 1, 1);
    private static Vec2f[] DEFAULT_COORDS = {
            new Vec2f(0, 0),
            new Vec2f(0, 1),
            new Vec2f(1, 1),
            new Vec2f(1, 0),
    };

    private final Map<String, ModelGroup> parts = Maps.newHashMap();

    private final List<Tensor> positions = Lists.newArrayList();
    private final List<Vec2f> texCoords = Lists.newArrayList();
    private final List<Tensor> normals = Lists.newArrayList();
    private final List<Tensor> colors = Lists.newArrayList();

    public final boolean detectCullableFaces;
    public final boolean diffuseLighting;
    public final boolean flipV;
    public final boolean ambientToFullbright;

    public final ResourceLocation modelLocation;

    @Nullable
    public final String materialLibraryOverrideLocation;


    public AnimatedOBJModel(LineReader reader, ModelSettings settings) throws IOException {
        this.modelLocation = settings.modelLocation;
        this.detectCullableFaces = settings.detectCullableFaces;
        this.diffuseLighting = settings.diffuseLighting;
        this.flipV = settings.flipV;
        this.ambientToFullbright = settings.ambientToFullbright;
        this.materialLibraryOverrideLocation = settings.materialLibraryOverrideLocation;

        // for relative references to material libraries
        String modelDomain = modelLocation.getNamespace();
        String modelPath = modelLocation.getPath();
        int lastSlash = modelPath.lastIndexOf('/');
        if (lastSlash >= 0)
            modelPath = modelPath.substring(0, lastSlash + 1); // include the '/'
        else
            modelPath = "";

        MaterialLibrary mtllib = MaterialLibrary.EMPTY;
        MaterialLibrary.Material currentMat = null;
        String currentSmoothingGroup = null;
        ModelGroup currentGroup = null;
        ModelObject currentObject = null;
        ModelMesh currentMesh = null;

        boolean objAboveGroup = false;

        if (materialLibraryOverrideLocation != null) {
            String lib = materialLibraryOverrideLocation;
            if (lib.contains(":"))
                mtllib = AnimatedOBJLoader.INSTANCE.loadMaterialLibrary(new ResourceLocation(lib));
            else
                mtllib = AnimatedOBJLoader.INSTANCE.loadMaterialLibrary(new ResourceLocation(modelDomain, modelPath + lib));
        }

        String[] line;
        while ((line = reader.readAndSplitLine(true)) != null) {
            switch (line[0]) {
                case "mtllib": // Loads material library
                {
                    if (materialLibraryOverrideLocation != null)
                        break;

                    String lib = line[1];
                    if (lib.contains(":"))
                        mtllib = AnimatedOBJLoader.INSTANCE.loadMaterialLibrary(new ResourceLocation(lib));
                    else
                        mtllib = AnimatedOBJLoader.INSTANCE.loadMaterialLibrary(new ResourceLocation(modelDomain, modelPath + lib));
                    break;
                }

                case "usemtl": // Sets the current material (starts new mesh)
                {
                    String mat = Strings.join(Arrays.copyOfRange(line, 1, line.length), " ");
                    MaterialLibrary.Material newMat = mtllib.getMaterial(mat);
                    if (!Objects.equals(newMat, currentMat)) {
                        currentMat = newMat;
                        if (currentMesh != null && currentMesh.mat == null && currentMesh.faces.size() == 0) {
                            currentMesh.mat = currentMat;
                        } else {
                            // Start new mesh
                            currentMesh = null;
                        }
                    }
                    break;
                }

                case "v": // Vertex
                    positions.add(parseVector4To3(line));
                    break;
                case "vt": // Vertex texcoord
                    texCoords.add(parseVector2(line));
                    break;
                case "vn": // Vertex normal
                    normals.add(parseVector3(line));
                    break;
                case "vc": // Vertex color (non-standard)
                    colors.add(parseVector4(line));
                    break;

                case "f": // Face
                {
                    if (currentMesh == null) {
                        currentMesh = new ModelMesh(currentMat, currentSmoothingGroup);
                        if (currentObject != null) {
                            currentObject.meshes.add(currentMesh);
                        } else {
                            if (currentGroup == null) {
                                currentGroup = new ModelGroup("");
                                parts.put("", currentGroup);
                            }
                            currentGroup.meshes.add(currentMesh);
                        }
                    }

                    int[][] vertices = new int[line.length - 1][];
                    for (int i = 0; i < vertices.length; i++) {
                        String vertexData = line[i + 1];
                        String[] vertexParts = vertexData.split("/");
                        int[] vertex = Arrays.stream(vertexParts).mapToInt(num -> Strings.isNullOrEmpty(num) ? 0 : Integer.parseInt(num)).toArray();
                        if (vertex[0] < 0) vertex[0] = positions.size() + vertex[0];
                        else vertex[0]--;
                        if (vertex.length > 1) {
                            if (vertex[1] < 0) vertex[1] = texCoords.size() + vertex[1];
                            else vertex[1]--;
                            if (vertex.length > 2) {
                                if (vertex[2] < 0) vertex[2] = normals.size() + vertex[2];
                                else vertex[2]--;
                                if (vertex.length > 3) {
                                    if (vertex[3] < 0) vertex[3] = colors.size() + vertex[3];
                                    else vertex[3]--;
                                }
                            }
                        }
                        vertices[i] = vertex;
                    }

                    currentMesh.faces.add(vertices);

                    break;
                }

                case "s": // Smoothing group (starts new mesh)
                {
                    String smoothingGroup = "off".equals(line[1]) ? null : line[1];
                    if (!Objects.equals(currentSmoothingGroup, smoothingGroup)) {
                        currentSmoothingGroup = smoothingGroup;
                        if (currentMesh != null && currentMesh.smoothingGroup == null && currentMesh.faces.size() == 0) {
                            currentMesh.smoothingGroup = currentSmoothingGroup;
                        } else {
                            // Start new mesh
                            currentMesh = null;
                        }
                    }
                    break;
                }

                case "g": {
                    String name = line[1];
                    if (objAboveGroup) {
                        currentObject = new ModelObject(currentGroup.name() + "/" + name);
                        currentGroup.parts.put(name, currentObject);
                    } else {
                        currentGroup = new ModelGroup(name);
                        parts.put(name, currentGroup);
                        currentObject = null;
                    }
                    // Start new mesh
                    currentMesh = null;
                    break;
                }

                case "o": {
                    String name = line[1];
                    if (objAboveGroup || currentGroup == null) {
                        objAboveGroup = true;

                        currentGroup = new ModelGroup(name);
                        parts.put(name, currentGroup);
                        currentObject = null;
                    } else {
                        currentObject = new ModelObject(currentGroup.name() + "/" + name);
                        currentGroup.parts.put(name, currentObject);
                    }
                    // Start new mesh
                    currentMesh = null;
                    break;
                }
            }
        }
    }

    public static Tensor parseVector4To3(String[] line) {
        switch (line.length) {
            case 1:
                return new Tensor(0, 0, 0);
            case 2:
                return new Tensor(Float.parseFloat(line[1]), 0, 0);
            case 3:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), 0);
            case 4:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), Float.parseFloat(line[3]));
            default: {
                Tensor vec4 = parseVector4(line);
                return new Tensor(
                        vec4.x() / vec4.w(),
                        vec4.y() / vec4.w(),
                        vec4.z() / vec4.w()
                );
            }
        }
    }

    public static Vec2f parseVector2(String[] line) {
        switch (line.length) {
            case 1:
                return new Vec2f(0, 0);
            case 2:
                return new Vec2f(Float.parseFloat(line[1]), 0);
            default:
                return new Vec2f(Float.parseFloat(line[1]), Float.parseFloat(line[2]));
        }
    }

    public static Tensor parseVector3(String[] line) {
        switch (line.length) {
            case 1:
                return new Tensor(0, 0, 0);
            case 2:
                return new Tensor(Float.parseFloat(line[1]), 0, 0);
            case 3:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), 0);
            default:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), Float.parseFloat(line[3]));
        }
    }

    public static Tensor parseVector4(String[] line) {
        switch (line.length) {
            case 1:
                return new Tensor(0, 0, 0, 1);
            case 2:
                return new Tensor(Float.parseFloat(line[1]), 0, 0, 1);
            case 3:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), 0, 1);
            case 4:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), Float.parseFloat(line[3]), 1);
            default:
                return new Tensor(Float.parseFloat(line[1]), Float.parseFloat(line[2]), Float.parseFloat(line[3]), Float.parseFloat(line[4]));
        }
    }

    @Override
    public Collection<? extends IModelGeometryPart> getParts() {
        return parts.values();
    }

    @Override
    public Optional<? extends IModelGeometryPart> getPart(String name) {
        return Optional.ofNullable(parts.get(name));
    }

    private Pair<BakedQuad, Direction> makeQuad(int[][] indices, int tintIndex, Tensor colorTint, Vector4f ambientColor, TextureAtlasSprite texture, TransformationMatrix transform) {
        boolean needsNormalRecalculation = false;
        for (int[] ints : indices) {
            needsNormalRecalculation |= ints.length < 3;
        }
        Tensor faceNormal = new Tensor(0, 0, 0);
        if (needsNormalRecalculation) {
            Tensor a = positions.get(indices[0][0]);
            Tensor ab = positions.get(indices[1][0]);
            Tensor ac = positions.get(indices[2][0]);
            Tensor abs = ab.clone();
            abs.sub(a);
            Tensor acs = ac.clone();
            acs.sub(a);
            // TODO: make it abs.cross(acs)
            abs = abs.cross(acs);
            abs.normalize();
            faceNormal = abs;
        }

        Tensor[] pos = new Tensor[4];
        Tensor[] norm = new Tensor[4];

        BakedQuadBuilder builder = new BakedQuadBuilder(texture);

        builder.setQuadTint(tintIndex);

        Vec2f uv2 = new Vec2f(0, 0);
        if (ambientToFullbright) {
            int fakeLight = (int) ((ambientColor.getX() + ambientColor.getY() + ambientColor.getZ()) * 15 / 3.0f);
            uv2 = new Vec2f((fakeLight << 4) / 32767.0f, (fakeLight << 4) / 32767.0f);
            builder.setApplyDiffuseLighting(fakeLight == 0);
        } else {
            builder.setApplyDiffuseLighting(diffuseLighting);
        }

        boolean hasTransform = !transform.isIdentity();
        // The incoming transform is referenced on the center of the block, but our coords are referenced on the corner
        TransformationMatrix transformation = hasTransform ? transform.blockCenterToCorner() : transform;

        for (int i = 0; i < 4; i++) {
            int[] index = indices[Math.min(i, indices.length - 1)];
            Tensor pos0 = positions.get(index[0]);
            Tensor position = pos0.clone();
            Vec2f texCoord = index.length >= 2 && texCoords.size() > 0 ? texCoords.get(index[1]) : DEFAULT_COORDS[i];
            Tensor norm0 = !needsNormalRecalculation && index.length >= 3 && normals.size() > 0 ? normals.get(index[2]) : faceNormal;
            Tensor normal = norm0;
            Tensor color = index.length >= 4 && colors.size() > 0 ? colors.get(index[3]) : COLOR_WHITE;
            if (hasTransform) {
                normal = norm0.clone();
                transformation.transformPosition(TensorUtils.toVector4(position));
                transformation.transformNormal(TensorUtils.toVector(normal));
            }
            Tensor tintedColor = new Tensor(
                    color.x() * colorTint.x(),
                    color.y() * colorTint.y(),
                    color.z() * colorTint.z(),
                    color.w() * colorTint.w());
            putVertexData(builder, position, texCoord, normal, tintedColor, uv2, texture);
            pos[i] = position;
            norm[i] = normal;
        }

        builder.setQuadOrientation(Direction.getFacingFromVector(norm[0].x(), norm[0].y(), norm[0].z()));

        Direction cull = null;
        if (detectCullableFaces) {
            if (MathHelper.epsilonEquals(pos[0].x(), 0) && // vertex.position.x
                    MathHelper.epsilonEquals(pos[1].x(), 0) &&
                    MathHelper.epsilonEquals(pos[2].x(), 0) &&
                    MathHelper.epsilonEquals(pos[3].x(), 0) &&
                    norm[0].x() < 0) // vertex.normal.x
            {
                cull = Direction.WEST;
            } else if (MathHelper.epsilonEquals(pos[0].x(), 1) && // vertex.position.x
                    MathHelper.epsilonEquals(pos[1].x(), 1) &&
                    MathHelper.epsilonEquals(pos[2].x(), 1) &&
                    MathHelper.epsilonEquals(pos[3].x(), 1) &&
                    norm[0].x() > 0) // vertex.normal.x
            {
                cull = Direction.EAST;
            } else if (MathHelper.epsilonEquals(pos[0].z(), 0) && // vertex.position.z
                    MathHelper.epsilonEquals(pos[1].z(), 0) &&
                    MathHelper.epsilonEquals(pos[2].z(), 0) &&
                    MathHelper.epsilonEquals(pos[3].z(), 0) &&
                    norm[0].z() < 0) // vertex.normal.z
            {
                cull = Direction.NORTH; // can never remember
            } else if (MathHelper.epsilonEquals(pos[0].z(), 1) && // vertex.position.z
                    MathHelper.epsilonEquals(pos[1].z(), 1) &&
                    MathHelper.epsilonEquals(pos[2].z(), 1) &&
                    MathHelper.epsilonEquals(pos[3].z(), 1) &&
                    norm[0].z() > 0) // vertex.normal.z
            {
                cull = Direction.SOUTH;
            } else if (MathHelper.epsilonEquals(pos[0].y(), 0) && // vertex.position.y
                    MathHelper.epsilonEquals(pos[1].y(), 0) &&
                    MathHelper.epsilonEquals(pos[2].y(), 0) &&
                    MathHelper.epsilonEquals(pos[3].y(), 0) &&
                    norm[0].y() < 0) // vertex.normal.z
            {
                cull = Direction.DOWN; // can never remember
            } else if (MathHelper.epsilonEquals(pos[0].y(), 1) && // vertex.position.y
                    MathHelper.epsilonEquals(pos[1].y(), 1) &&
                    MathHelper.epsilonEquals(pos[2].y(), 1) &&
                    MathHelper.epsilonEquals(pos[3].y(), 1) &&
                    norm[0].y() > 0) // vertex.normal.y
            {
                cull = Direction.UP;
            }
        }

        return Pair.of(builder.build(), cull);
    }

    private void putVertexData(IVertexConsumer consumer, Tensor position0, Vec2f texCoord0, Tensor normal0, Tensor color0, Vec2f uv2, TextureAtlasSprite texture) {
        ImmutableList<VertexFormatElement> elements = consumer.getVertexFormat().getElements();
        for (int j = 0; j < elements.size(); j++) {
            VertexFormatElement e = elements.get(j);
            switch (e.getUsage()) {
                case POSITION:
                    consumer.put(j, position0.floatX(), position0.floatY(), position0.floatZ(), position0.floatW());
                    break;
                case COLOR:
                    consumer.put(j, color0.floatX(), color0.floatY(), color0.floatZ(), color0.floatW());
                    break;
                case UV:
                    switch (e.getIndex()) {
                        case 0:
                            consumer.put(j,
                                    texture.getInterpolatedU(texCoord0.x * 16),
                                    texture.getInterpolatedV((flipV ? (1 - texCoord0.y) : texCoord0.y) * 16)
                            );
                            break;
                        case 2:
                            consumer.put(j, uv2.x, uv2.y);
                            break;
                        default:
                            consumer.put(j);
                            break;
                    }
                    break;
                case NORMAL:
                    consumer.put(j, normal0.floatX(), normal0.floatY(), normal0.floatZ());
                    break;
                default:
                    consumer.put(j);
                    break;
            }
        }
    }

    public class ModelObject implements IModelGeometryPart {
        public final String name;

        List<ModelMesh> meshes = Lists.newArrayList();

        ModelObject(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void addQuads(IModelConfiguration owner, IModelBuilder<?> modelBuilder, ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, IModelTransform modelTransform, ResourceLocation modelLocation) {
            for (ModelMesh mesh : meshes) {
                MaterialLibrary.Material mat = mesh.mat;
                if (mat == null)
                    continue;
                TextureAtlasSprite texture = spriteGetter.apply(ModelLoaderRegistry.resolveTexture(mat.diffuseColorMap, owner));
                int tintIndex = mat.diffuseTintIndex;
                Tensor colorTint = TensorUtils.fromVector4(mat.diffuseColor);

                for (int[][] face : mesh.faces) {
                    Pair<BakedQuad, Direction> quad = makeQuad(face, tintIndex, colorTint, mat.ambientColor, texture, modelTransform.getRotation());
                    if (quad.getRight() == null)
                        modelBuilder.addGeneralQuad(quad.getLeft());
                    else
                        modelBuilder.addFaceQuad(quad.getRight(), quad.getLeft());
                }
            }
        }

        @Override
        public Collection<Material> getTextures(IModelConfiguration owner, Function<ResourceLocation, IUnbakedModel> modelGetter, Set<com.mojang.datafixers.util.Pair<String, String>> missingTextureErrors) {
            return meshes.stream().map(mesh -> ModelLoaderRegistry.resolveTexture(mesh.mat.diffuseColorMap, owner)).collect(Collectors.toSet());
        }
    }

    public class ModelGroup extends ModelObject {
        final Map<String, ModelObject> parts = Maps.newHashMap();

        ModelGroup(String name) {
            super(name);
        }

        public Collection<? extends IModelGeometryPart> getParts() {
            return parts.values();
        }

        @Override
        public void addQuads(IModelConfiguration owner, IModelBuilder<?> modelBuilder, ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, IModelTransform modelTransform, ResourceLocation modelLocation) {
            super.addQuads(owner, modelBuilder, bakery, spriteGetter, modelTransform, modelLocation);

            getParts().stream().filter(part -> owner.getPartVisibility(part))
                    .forEach(part -> part.addQuads(owner, modelBuilder, bakery, spriteGetter, modelTransform, modelLocation));
        }

        @Override
        public Collection<Material> getTextures(IModelConfiguration owner, Function<ResourceLocation, IUnbakedModel> modelGetter, Set<com.mojang.datafixers.util.Pair<String, String>> missingTextureErrors) {
            Set<Material> combined = Sets.newHashSet();
            combined.addAll(super.getTextures(owner, modelGetter, missingTextureErrors));
            for (IModelGeometryPart part : getParts())
                combined.addAll(part.getTextures(owner, modelGetter, missingTextureErrors));
            return combined;
        }
    }

    private class ModelMesh {
        @Nullable
        public MaterialLibrary.Material mat;
        @Nullable
        public String smoothingGroup;
        public final List<int[][]> faces = Lists.newArrayList();

        public ModelMesh(@Nullable MaterialLibrary.Material currentMat, @Nullable String currentSmoothingGroup) {
            this.mat = currentMat;
            this.smoothingGroup = currentSmoothingGroup;
        }
    }

    public static class ModelSettings {
        @Nonnull
        public final ResourceLocation modelLocation;
        public final boolean detectCullableFaces;
        public final boolean diffuseLighting;
        public final boolean flipV;
        public final boolean ambientToFullbright;
        @Nullable
        public final String materialLibraryOverrideLocation;

        public ModelSettings(@Nonnull ResourceLocation modelLocation, boolean detectCullableFaces, boolean diffuseLighting, boolean flipV, boolean ambientToFullbright,
                             @Nullable String materialLibraryOverrideLocation) {
            this.modelLocation = modelLocation;
            this.detectCullableFaces = detectCullableFaces;
            this.diffuseLighting = diffuseLighting;
            this.flipV = flipV;
            this.ambientToFullbright = ambientToFullbright;
            this.materialLibraryOverrideLocation = materialLibraryOverrideLocation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModelSettings that = (ModelSettings) o;
            return equals(that);
        }

        public boolean equals(@Nonnull ModelSettings that) {
            return detectCullableFaces == that.detectCullableFaces &&
                    diffuseLighting == that.diffuseLighting &&
                    flipV == that.flipV &&
                    ambientToFullbright == that.ambientToFullbright &&
                    modelLocation.equals(that.modelLocation) &&
                    Objects.equals(materialLibraryOverrideLocation, that.materialLibraryOverrideLocation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(modelLocation, detectCullableFaces, diffuseLighting, flipV, ambientToFullbright, materialLibraryOverrideLocation);
        }
    }
}