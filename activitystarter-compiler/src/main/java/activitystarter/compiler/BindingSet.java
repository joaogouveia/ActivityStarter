package activitystarter.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import static activitystarter.compiler.Utills.capitalizeFirstLetter;
import static com.google.auto.common.MoreElements.getPackage;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

final class BindingSet {

    private static final ClassName UI_THREAD = ClassName.get("android.support.annotation", "UiThread");
    private static final ClassName INTENT = ClassName.get("android.content", "Intent");
    private static final ClassName CONTEXT = ClassName.get("android.content", "Context");

    private final TypeName targetTypeName;
    private final ClassName bindingClassName;
    private final List<ArgumentBinding> argumentBindings;
    private final boolean isFinal;

    BindingSet(TypeElement enclosingElement) {
        TypeName targetType = TypeName.get(enclosingElement.asType());
        if (targetType instanceof ParameterizedTypeName) {
            targetType = ((ParameterizedTypeName) targetType).rawType;
        }
        targetTypeName = targetType;
        String packageName = getPackage(enclosingElement).getQualifiedName().toString();
        String className = enclosingElement.getQualifiedName().toString().substring(packageName.length() + 1).replace('.', '$');
        bindingClassName = ClassName.get(packageName, className + "Starter");
        isFinal = enclosingElement.getModifiers().contains(Modifier.FINAL);
        argumentBindings = new ArrayList<>();
    }

    public List<List<ArgumentBinding>> getArgumentBindingVariants() {
        ArrayList<List<ArgumentBinding>> list = new ArrayList<>();
        list.add(new ArrayList<ArgumentBinding>());

        for (ArgumentBinding b: argumentBindings) {
            if(b.isRequired()) {
                for (List<ArgumentBinding> sublist: list) {
                    sublist.add(b);
                }
            } else {
                List<List<ArgumentBinding>> newElements = new ArrayList<>();
                for (List<ArgumentBinding> sublist: list) {
                    List<ArgumentBinding> sublistCopy = copy(sublist);
                    sublistCopy.add(b);
                    newElements.add(sublistCopy);
                }
                list.addAll(newElements);
            }
        }
        return list;
    }

    public List<ArgumentBinding> copy(List<ArgumentBinding> oldList) {
        List<ArgumentBinding> list = new ArrayList<>();
        list.addAll(oldList);
        return list;
    }

    void addArgumentBinding(ArgumentBinding argumentBinding) {
        argumentBindings.add(argumentBinding);
    }

    JavaFile brewJava() {
        return JavaFile.builder(bindingClassName.packageName(), getActivityStarterSpec())
                .addFileComment("Generated code from ActivityStarter. Do not modify!")
                .build();
    }

    private TypeSpec getActivityStarterSpec() {
        TypeSpec.Builder result = TypeSpec
                .classBuilder(bindingClassName.simpleName())
                .addModifiers(PUBLIC);

        if (isFinal) result.addModifiers(FINAL);

        result.addMethod(createFillFieldsMethod());

        for(List<ArgumentBinding> variant : getArgumentBindingVariants()) {
            result.addMethod(createStartActivityMethod(variant));
            result.addMethod(createGetIntentMethod(variant));
        }

        return result.build();
    }

    private MethodSpec createStartActivityMethod(List<ArgumentBinding> variant) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("start")
                .addAnnotation(UI_THREAD)
                .addParameter(CONTEXT, "context")
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        for (ArgumentBinding arg : variant) {
            builder.addParameter(arg.getType(), arg.getName());
        }

        builder.addStatement("$T intent = new Intent(context, $T.class)", INTENT, targetTypeName);
        for (ArgumentBinding arg : variant) {
            builder.addStatement("intent.putExtra(\"" + arg.getName() + "Arg\", " + arg.getName() + ")");
        }
        builder.addStatement("context.startActivity(intent)");
        return builder.build();
    }

    private MethodSpec createGetIntentMethod(List<ArgumentBinding> variant) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("getIntent")
                .addAnnotation(UI_THREAD)
                .addParameter(CONTEXT, "context")
                .returns(INTENT)
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        for (ArgumentBinding arg : variant) {
            builder.addParameter(arg.getType(), arg.getName());
        }

        builder.addStatement("$T intent = new Intent(context, $T.class)", INTENT, targetTypeName);
        for (ArgumentBinding arg : variant) {
            builder.addStatement("intent.putExtra(\"" + arg.getName() + "Arg\", " + arg.getName() + ")");
        }
        builder.addStatement("return intent");
        return builder.build();
    }

    private MethodSpec createFillFieldsMethod() {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("fill")
                .addAnnotation(UI_THREAD)
                .addParameter(targetTypeName, "activity")
                .addModifiers(PUBLIC)
                .addModifiers(STATIC);

        if (argumentBindings.size() > 0)
            builder.addStatement("Intent intent = activity.getIntent()");

        for (ArgumentBinding arg : argumentBindings) {
            String fieldName = arg.getName();
            String keyName = fieldName + "Arg";

            if (arg.isBySetter())
                builder.addStatement("if(intent.hasExtra(\"" + keyName + "\")) activity.set" + capitalizeFirstLetter(fieldName) + "(" + getIntentGetterFor(arg, keyName) + ")");
            else
                builder.addStatement("if(intent.hasExtra(\"" + keyName + "\")) activity." + fieldName + " = " + getIntentGetterFor(arg, keyName));
        }

        return builder.build();
    }

    private String getIntentGetterFor(ArgumentBinding arg, String keyName) {
        TypeName name = arg.getType();
        if (name.equals(TypeName.get(String.class)))
            return "intent.getStringExtra(\"" + keyName + "\")";
        else if (name.equals(TypeName.INT))
            return "intent.getIntExtra(\"" + keyName + "\", -1)";
        else if (name.equals(TypeName.FLOAT))
            return "intent.getFloatExtra(\"" + keyName + "\", -1F)";
        else if (name.equals(TypeName.BOOLEAN))
            return "intent.getBooleanExtra(\"" + keyName + "\", false)";
        else if (name.equals(TypeName.DOUBLE))
            return "intent.getDoubleExtra(\"" + keyName + "\", -1D)";
        else if (name.equals(TypeName.CHAR))
            return "intent.getCharExtra(\"" + keyName + "\", 'a')";
        else if (IsSubtypeHelper.isSubtypeOfType(arg.getElementType(), "android.os.Parcelable"))
            return "("+name+") intent.getParcelableExtra(\"" + keyName + "\")";
        else if (IsSubtypeHelper.isSubtypeOfType(arg.getElementType(), "java.io.Serializable"))
            return "("+name+") intent.getSerializableExtra(\"" + keyName + "\")";
        else
            throw new Error("Illegal field type" + arg.getType());
    }
}
