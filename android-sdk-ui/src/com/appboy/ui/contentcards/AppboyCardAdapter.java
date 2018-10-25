package com.appboy.ui.contentcards;

import android.content.Context;
import android.os.Handler;
import android.support.annotation.VisibleForTesting;
import android.support.v7.util.DiffUtil;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.ViewGroup;

import com.appboy.models.cards.Card;
import com.appboy.support.AppboyLogger;
import com.appboy.ui.contentcards.handlers.IContentCardsViewBindingHandler;
import com.appboy.ui.contentcards.recycler.ItemTouchHelperAdapter;
import com.appboy.ui.contentcards.view.ContentCardViewHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppboyCardAdapter extends RecyclerView.Adapter<ContentCardViewHolder> implements ItemTouchHelperAdapter {
  private static final String TAG = AppboyLogger.getAppboyLogTag(AppboyCardAdapter.class);

  private final Context mContext;
  private final Handler mHandler;
  private final LinearLayoutManager mLayoutManager;
  private final IContentCardsViewBindingHandler mContentCardsViewBindingHandler;

  private List<Card> mCardData;
  private Set<String> mImpressedCardIds = new HashSet<String>();

  public AppboyCardAdapter(Context context, LinearLayoutManager layoutManager, List<Card> cardData, IContentCardsViewBindingHandler contentCardsViewBindingHandler) {
    mContext = context;
    mCardData = cardData;
    mHandler = new Handler();
    mLayoutManager = layoutManager;
    mContentCardsViewBindingHandler = contentCardsViewBindingHandler;

    // We use stable ids to ensure that the same ViewHolder gets used with the same item.
    setHasStableIds(true);
  }

  @Override
  public ContentCardViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
    return mContentCardsViewBindingHandler.onCreateViewHolder(mContext, mCardData, viewGroup, viewType);
  }

  @Override
  public void onBindViewHolder(ContentCardViewHolder viewHolder, int position) {
    mContentCardsViewBindingHandler.onBindViewHolder(mContext, mCardData, viewHolder, position);
  }

  @Override
  public int getItemViewType(int position) {
    return mContentCardsViewBindingHandler.getItemViewType(mContext, mCardData, position);
  }

  @Override
  public int getItemCount() {
    return mCardData.size();
  }

  @Override
  public void onItemDismiss(int position) {
    // Note that the ordering of these operations is important. We can't notify of item removal until the item has
    // actually been removed. Additionally, we can only call the card action listener after the card removal
    // as per the onContentCardDismissed() specification.
    Card removedCard = mCardData.remove(position);
    removedCard.setIsDismissed(true);
    notifyItemRemoved(position);
    AppboyContentCardsManager.getInstance().getContentCardsActionListener().onContentCardDismissed(mContext, removedCard);
  }

  @Override
  public boolean isItemDismissable(int position) {
    if (mCardData.isEmpty()) {
      return false;
    }
    return mCardData.get(position).getIsDismissible();
  }

  @Override
  public void onViewAttachedToWindow(ContentCardViewHolder holder) {
    // Note that onViewAttachedToWindow() is called right before a view is "visible".
    // I.e. the Layout Manager has not yet updated its "first/last visible item position"
    super.onViewAttachedToWindow(holder);

    // If the cards are empty just return
    if (mCardData.isEmpty()) {
      return;
    }

    final int adapterPosition = holder.getAdapterPosition();

    if (adapterPosition == RecyclerView.NO_POSITION || !isAdapterPositionOnScreen(adapterPosition)) {
      AppboyLogger.v(TAG, "The card at position " + adapterPosition + " isn't on screen or does not have a valid adapter position. Not logging impression.");
      return;
    }

    logImpression(mCardData.get(adapterPosition));
  }

  @Override
  public void onViewDetachedFromWindow(ContentCardViewHolder holder) {
    // Note that onViewDetachedFromWindow() is called right before a view is technically "non-visible".
    // I.e. once a view goes off-screen, it's first detached from the window, then the Layout Manager updates its "first/last visible item position"
    // Also note that when the RecyclerView is paused, onViewDetachedFromWindow() is called for all attached views.
    super.onViewDetachedFromWindow(holder);

    // If the cards are empty just return
    if (mCardData.isEmpty()) {
      return;
    }

    final int adapterPosition = holder.getAdapterPosition();

    // RecyclerView will attach some number of views above/below the visible views on screen. However, when onViewDetachedFromWindow() is called
    // for each of those views, regardless of whether it was visible or not. We do not want to mistakenly mark some cards as "read" if the user never actually saw them.
    // E.g. if views [A B C] were visible on screen, RecyclerView could have attached views ( A B C D E ). Without this check, we would mistakenly mark views D & E as read.
    if (adapterPosition == RecyclerView.NO_POSITION || !isAdapterPositionOnScreen(adapterPosition)) {
      AppboyLogger.v(TAG, "The card at position " + adapterPosition + " isn't on screen or does not have a valid adapter position. Not marking as read.");
      return;
    }

    // Get the card at this adapter position
    Card cardAtPosition = mCardData.get(adapterPosition);
    cardAtPosition.setIndicatorHighlighted(true);

    // Mark as changed
    mHandler.post(new Runnable() {
      @Override
      public void run() {
        notifyItemChanged(adapterPosition);
      }
    });
  }

  @Override
  public long getItemId(int position) {
    return mCardData.get(position).getId().hashCode();
  }

  public synchronized void replaceCards(List<Card> newCardData) {
    CardListDiffCallback diffCallback = new CardListDiffCallback(mCardData, newCardData);
    DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

    mCardData.clear();
    mCardData.addAll(newCardData);

    // The diff dispatch will call the adapter notify methods
    diffResult.dispatchUpdatesTo(this);
  }

  /**
   * Marks every on-screen card as read.
   */
  public void markOnScreenCardsAsRead() {
    if (mCardData.isEmpty()) {
      AppboyLogger.d(TAG, "Card list is empty. Not marking on-screen cards as read.");
      return;
    }
    final int firstVisibleIndex = mLayoutManager.findFirstVisibleItemPosition();
    final int lastVisibleIndex = mLayoutManager.findLastVisibleItemPosition();

    // Either case could arise if there are no items in the adapter, i.e. no cards are visible since none exist
    if (firstVisibleIndex < 0 || lastVisibleIndex < 0) {
      AppboyLogger.d(TAG, "Not marking all on-screen cards as read. "
          + "Either the first or last index is negative. First visible: " + firstVisibleIndex + " . Last visible: " + lastVisibleIndex);
      return;
    }

    // We want to mark all cards in the inclusive range of [first, last] as read
    for (int i = firstVisibleIndex; i <= lastVisibleIndex; i++) {
      mCardData.get(i).setIndicatorHighlighted(true);
    }

    mHandler.post(new Runnable() {
      @Override
      public void run() {
        // We add 1 since the number of items since if indices 0 & 1 were changed, then a total of 2 items were changed.
        final int itemsChangedCount = (lastVisibleIndex - firstVisibleIndex) + 1;
        notifyItemRangeChanged(firstVisibleIndex, itemsChangedCount);
      }
    });
  }

  /**
   * @return a {@link List} snapshot of the impressed card ids.
   */
  public List<String> getImpressedCardIds() {
    return new ArrayList<String>(mImpressedCardIds);
  }

  /**
   * Provides a list of the impressed card ids. Used when restoring from a saved state.
   *
   * @param impressedCardIds a list of the card ids with impressions.
   */
  public void setImpressedCardIds(List<String> impressedCardIds) {
    mImpressedCardIds = new HashSet<String>(impressedCardIds);
  }

  /**
   * Returns whether the card at the adapter position is a control card.
   * </p>
   * @see Card#isControl()
   *
   * @param adapterPosition A valid adapter position for the card data. Invalid positions will return false.
   */
  public boolean isControlCardAtPosition(int adapterPosition) {
    if (adapterPosition < 0 || adapterPosition >= mCardData.size()) {
      return false;
    }
    return mCardData.get(adapterPosition).isControl();
  }

  /**
   * Gets whether the item at a position is visible on screen.
   */
  @VisibleForTesting
  boolean isAdapterPositionOnScreen(int adapterPosition) {
    // At various points in the layout/scroll phase of the RecyclerView, the values returned by "find*VisibleItem" and "find*CompletelyVisibleItem" will either be
    // RecyclerView.NO_POSITION (which is -1), differ by 1, or be equal. Additionally, the "find*CompletelyVisible" value will sometimes update before the "find*Visible" value.
    // To accommodate each of these cases, we'll just take the min of the "first" values and the max of the "last" values.

    int firstItemPosition = Math.min(mLayoutManager.findFirstVisibleItemPosition(), mLayoutManager.findFirstCompletelyVisibleItemPosition());
    int lastItemPosition = Math.max(mLayoutManager.findLastVisibleItemPosition(), mLayoutManager.findLastCompletelyVisibleItemPosition());

    return firstItemPosition <= adapterPosition && lastItemPosition >= adapterPosition;
  }

  /**
   * Logs an impression on the card. Performs a check against the known set previously impressed card ids.
   * Will also set the viewed state of the card to true.
   */
  @VisibleForTesting
  void logImpression(Card card) {
    if (!mImpressedCardIds.contains(card.getId())) {
      card.logImpression();
      mImpressedCardIds.add(card.getId());
      AppboyLogger.v(TAG, "Logged impression for card " + card.getId());
    } else {
      AppboyLogger.v(TAG, "Already counted impression for card " + card.getId());
    }
    if (!card.getViewed()) {
      card.setViewed(true);
    }
  }

  /**
   * A {@link Card} based implementation of the {@link DiffUtil.Callback}. This implementation assumes cards with the same id
   * are equivalent content-wise and visually.
   */
  private class CardListDiffCallback extends DiffUtil.Callback {
    private final List<Card> mOldCards;
    private final List<Card> mNewCards;

    CardListDiffCallback(List<Card> oldCards, List<Card> newCards) {
      mOldCards = oldCards;
      mNewCards = newCards;
    }

    @Override
    public int getOldListSize() {
      return mOldCards.size();
    }

    @Override
    public int getNewListSize() {
      return mNewCards.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
      return doItemsShareIds(oldItemPosition, newItemPosition);
    }

    @Override
    public boolean areContentsTheSame(final int oldItemPosition, final int newItemPosition) {
      return doItemsShareIds(oldItemPosition, newItemPosition);
    }

    private boolean doItemsShareIds(int oldItemPosition, int newItemPosition) {
      return mOldCards.get(oldItemPosition).getId().equals(mNewCards.get(newItemPosition).getId());
    }
  }
}
