package me.creepinson.mod.api.network;

import net.minecraft.util.EnumFacing;

public interface INetworkProducer<T> extends INetworkedTile<T> {
    /**
     * @return How much energy does this TileEntity want?
     */
    T getRequest(EnumFacing direction);
}