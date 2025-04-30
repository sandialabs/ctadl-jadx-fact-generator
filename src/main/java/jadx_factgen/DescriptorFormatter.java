package jadx_factgen;

import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DescriptorFormatter {
    private static final Logger LOG = LoggerFactory.getLogger(DescriptorFormatter.class);

    public static String toDescriptor(ClassInfo cls) {
        return toDescriptor(cls.getType());
    }

    public static String toDescriptor(ClassNode cls) {
        // unsure if this matters versus just flowing through to the ClassInfo mth (seems to preserve generics info)
        //return toDescriptor(cls.getType());
        return toDescriptor(cls.getClassInfo());
    }

    public static String toDescriptor(MethodNode mth) {
        StringBuilder s = new StringBuilder();
        s.append('(');
        for (ArgType argty : mth.getArgTypes()) {
            s.append(toDescriptor(argty));
        }
        s.append(')');
        s.append(toDescriptor(mth.getReturnType()));
        return s.toString();
    }

    public static String toDescriptor(MethodInfo mth) {
        StringBuilder s = new StringBuilder();
        s.append('(');
        for (ArgType argty : mth.getArgumentsTypes()) {
            s.append(toDescriptor(argty));
        }
        s.append(')');
        s.append(toDescriptor(mth.getReturnType()));
        return s.toString();
    }

    public static String toDescriptor(ArgType ty) {
        if (ty.isPrimitive()) {
            return ty.getPrimitiveType().getShortName();
        } else if (ty.isObject()) {
            return "L" + ty.getObject().replace(".", "/") + ";";
        } else if (ty.isArray()) {
            StringBuilder s = new StringBuilder();
            ArgType root = ty.getArrayRootElement();
            int dim = ty.getArrayDimension();

            s.append("[".repeat(Math.max(0, dim)));
            s.append(toDescriptor(root));
            return s.toString();
        } else if (ty.isGeneric()) {
            LOG.error("generic");
        } else if (ty.isGenericType()) {
            LOG.error("generic type");
        } else if (ty.isWildcard()) {
            LOG.error("wildcard");
        } else if (!ty.isTypeKnown()) {
            return ty.toString(); // idk
        }
        LOG.error("can't handle: " + ty);
        throw new NullPointerException();
    }
}
