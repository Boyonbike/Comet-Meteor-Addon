# Shulker Icons

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **shulker-icons**. Open **Meteor → Comet → shulker-icons**.

Enable **Comet → shulker-icons** to add a small item icon over shulker boxes in inventory and container GUIs, including creative slots and the carried stack. By default it shows the item with the greatest total quantity inside the box, summed across slots. Ties use the first occupied slot. Empty boxes, or boxes whose contents the server does not send, show no icon. All shulker colours work.

The overlay defaults to half size in the top-right corner. Change **icon-scale** and **icon-corner** in General to adjust it.

Under **Combos**, click **Edit Combos → Add Combo**. Expand the combo, give it a name, select **required-items**, then click **Select Icon**. A match needs at least one of every selected item; extra contents are allowed. For example, require a netherite helmet, chestplate, leggings and boots, plus a totem, then select a diamond sword as the icon for that kit. Matching uses item types, ignoring damage, enchantments and custom names.

Each combo has a **Select Colour** button with a **Solid Colour** checkbox immediately beside it:

- **Unchecked:** use the original item design. **Select Icon** offers every normal item icon, including every block variant.
- **Checked:** use a flat silhouette in the selected colour, with a live preview. Blocks with matching loaded model geometry share one picker entry; block texture and colour variants do not repeat the same shape. Searching a grouped block's name still finds that shared entry. Distinct model shapes remain separate. The list is rebuilt when the picker opens to respect resource-pack changes.

Changing the colour stores it; the checkbox controls whether to use it. Switching the checkbox off restores the original item texture and keeps the chosen colour for next time. Changes refresh the preview and cached overlays immediately. The selected colour belongs to the combo, so two kits can use the same shape in different colours. Existing saved styles migrate to the corresponding checkbox state, and the automatic most-common-item fallback keeps its normal item icon.

Combos override the most-common item. The first enabled matching combo wins; **Move Up** and **Move Down** change priority. Disable or delete individual combos as needed. An empty required-items list never matches. With no matching combo, the normal most-common icon appears. Rules save with Meteor configurations and participate in module copy/paste and reset; the **Reset** button beside **Edit Combos** asks before deleting every combo.

If the solid-colour shader cannot be compiled (for example under an incompatible resource pack), coloured combos show their normal item icon and the log says why; the shader is retried after the next resource reload.
