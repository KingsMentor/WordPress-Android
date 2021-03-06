package org.wordpress.android.ui.main;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.support.annotation.LayoutRes;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.AccountStore;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.SiteUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.widgets.WPNetworkImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

public class SitePickerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface OnSiteClickListener {
        void onSiteClick(SiteRecord site);
        boolean onSiteLongClick(SiteRecord site);
    }

    interface OnSelectedCountChangedListener {
        void onSelectedCountChanged(int numSelected);
    }

    public interface OnDataLoadedListener {
        void onBeforeLoad(boolean isEmpty);
        void onAfterLoad();
    }

    public interface HeaderHandler {
        RecyclerView.ViewHolder onCreateViewHolder(LayoutInflater layoutInflater, ViewGroup parent, boolean attachToRoot);
        void onBindViewHolder(final RecyclerView.ViewHolder holder, int numberOfSites);
    }

    private final int mTextColorNormal;
    private final int mTextColorHidden;
    private final @LayoutRes int mItemLayoutReourceId;

    private static int mBlavatarSz;

    private SiteList mSites = new SiteList();
    private final int mCurrentLocalId;

    private final Drawable mSelectedItemBackground;

    private final LayoutInflater mInflater;
    private final HashSet<Integer> mSelectedPositions = new HashSet<>();
    private final HeaderHandler mHeaderHandler;

    private boolean mIsMultiSelectEnabled;
    private final boolean mIsInSearchMode;
    private boolean mShowHiddenSites = false;
    private boolean mShowSelfHostedSites = true;
    private String mLastSearch;
    private SiteList mAllSites;
    private ArrayList<Integer> mIgnoreSitesIds;

    private OnSiteClickListener mSiteSelectedListener;
    private OnSelectedCountChangedListener mSelectedCountListener;
    private OnDataLoadedListener mDataLoadedListener;

    private boolean mIsSingleItemSelectionEnabled;
    private int mSelectedItemPos;

    // show recently picked first if there are at least this many blogs
    private static final int RECENTLY_PICKED_THRESHOLD = 11;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    @Inject AccountStore mAccountStore;
    @Inject SiteStore mSiteStore;

    class SiteViewHolder extends RecyclerView.ViewHolder {
        private final ViewGroup layoutContainer;
        private final TextView txtTitle;
        private final TextView txtDomain;
        private final WPNetworkImageView imgBlavatar;
        private final View divider;
        private Boolean isSiteHidden;
        private final RadioButton selectedRadioButton;

        public SiteViewHolder(View view) {
            super(view);
            layoutContainer = (ViewGroup) view.findViewById(R.id.layout_container);
            txtTitle = (TextView) view.findViewById(R.id.text_title);
            txtDomain = (TextView) view.findViewById(R.id.text_domain);
            imgBlavatar = (WPNetworkImageView) view.findViewById(R.id.image_blavatar);
            divider = view.findViewById(R.id.divider);
            isSiteHidden = null;
            selectedRadioButton = (RadioButton) view.findViewById(R.id.radio_selected);
        }
    }

    public SitePickerAdapter(Context context,
            @LayoutRes int itemLayoutResourceId,
            int currentLocalBlogId,
            String lastSearch,
            boolean isInSearchMode,
            OnDataLoadedListener dataLoadedListener) {
        this(context, itemLayoutResourceId, currentLocalBlogId, lastSearch, isInSearchMode, dataLoadedListener, null,
                null);
    }

    public SitePickerAdapter(Context context,
            @LayoutRes int itemLayoutResourceId,
            int currentLocalBlogId,
            String lastSearch,
            boolean isInSearchMode,
            OnDataLoadedListener dataLoadedListener,
            HeaderHandler headerHandler,
            ArrayList<Integer> ignoreSitesIds) {
        super();
        ((WordPress) context.getApplicationContext()).component().inject(this);

        setHasStableIds(true);

        mLastSearch = StringUtils.notNullStr(lastSearch);
        mAllSites = new SiteList();
        mIsInSearchMode = isInSearchMode;
        mItemLayoutReourceId = itemLayoutResourceId;
        mCurrentLocalId = currentLocalBlogId;
        mInflater = LayoutInflater.from(context);
        mDataLoadedListener = dataLoadedListener;

        mBlavatarSz = context.getResources().getDimensionPixelSize(R.dimen.blavatar_sz);
        mTextColorNormal = context.getResources().getColor(R.color.grey_dark);
        mTextColorHidden = context.getResources().getColor(R.color.grey);

        mSelectedItemBackground = new ColorDrawable(context.getResources().getColor(R.color.grey_lighten_20_translucent_50));

        mHeaderHandler = headerHandler;
        mSelectedItemPos = getPositionOffset();

        mIgnoreSitesIds = ignoreSitesIds;

        loadSites();
    }

    @Override
    public int getItemCount() {
        return (mHeaderHandler != null ? 1 : 0) + mSites.size();
    }

    public int getSitesCount(){
        return mSites.size();
    }

    @Override
    public long getItemId(int position) {
        if (mHeaderHandler != null && position == 0) {
            return RecyclerView.NO_ID;
        } else {
            return getItem(position).localId;
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (mHeaderHandler == null) {
            return VIEW_TYPE_ITEM;
        } else {
            return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
        }
    }

    private SiteRecord getItem(int position) {
        return mSites.get(position - getPositionOffset());
    }

    private int getPositionOffset(){
        return (mHeaderHandler == null ? 0 : 1);
    }

    void setOnSelectedCountChangedListener(OnSelectedCountChangedListener listener) {
        mSelectedCountListener = listener;
    }

    public void setOnSiteClickListener(OnSiteClickListener listener) {
        mSiteSelectedListener = listener;
        notifyDataSetChanged();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            return mHeaderHandler.onCreateViewHolder(mInflater, parent, false);
        } else {
            View itemView = mInflater.inflate(mItemLayoutReourceId, parent, false);
            return new SiteViewHolder(itemView);
        }
    }

    @Override
    public void onBindViewHolder(final RecyclerView.ViewHolder viewHolder, int position) {
        int viewType = getItemViewType(position);

        if (viewType == VIEW_TYPE_HEADER) {
            mHeaderHandler.onBindViewHolder(viewHolder, getItemCount() - 1);
            return;
        }

        SiteRecord site = getItem(position);

        final SiteViewHolder holder = (SiteViewHolder) viewHolder;
        holder.txtTitle.setText(site.getBlogNameOrHomeURL());
        holder.txtDomain.setText(site.homeURL);
        holder.imgBlavatar.setImageUrl(site.blavatarUrl, WPNetworkImageView.ImageType.BLAVATAR);

        if (site.localId == mCurrentLocalId || (mIsMultiSelectEnabled && isItemSelected(position))) {
            holder.layoutContainer.setBackgroundDrawable(mSelectedItemBackground);
        } else {
            holder.layoutContainer.setBackgroundDrawable(null);
        }

        // different styling for visible/hidden sites
        if (holder.isSiteHidden == null || holder.isSiteHidden != site.isHidden) {
            holder.isSiteHidden = site.isHidden;
            holder.txtTitle.setTextColor(site.isHidden ? mTextColorHidden : mTextColorNormal);
            holder.txtTitle.setTypeface(holder.txtTitle.getTypeface(), site.isHidden ? Typeface.NORMAL : Typeface.BOLD);
            holder.imgBlavatar.setAlpha(site.isHidden ? 0.5f : 1f);
        }

        if (holder.divider != null) {
            // only show divider after last recent pick
            boolean showDivider = site.isRecentPick
                    && !mIsInSearchMode
                    && position < getItemCount() - 1
                    && !getItem(position + 1).isRecentPick;
            holder.divider.setVisibility(showDivider ?  View.VISIBLE : View.GONE);
        }

        if (mIsMultiSelectEnabled || mSiteSelectedListener != null) {
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    int clickedPosition = holder.getAdapterPosition();
                    if (isValidPosition(clickedPosition)) {
                        if (mIsMultiSelectEnabled) {
                            toggleSelection(clickedPosition);
                        } else if (mSiteSelectedListener != null) {
                            mSiteSelectedListener.onSiteClick(getItem(clickedPosition));
                        }
                    } else {
                        AppLog.w(AppLog.T.MAIN, "site picker > invalid clicked position " + clickedPosition);
                    }
                }
            });

            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    int clickedPosition = holder.getAdapterPosition();
                    if (isValidPosition(clickedPosition)) {
                        if (mIsMultiSelectEnabled) {
                            toggleSelection(clickedPosition);
                            return true;
                        } else if (mSiteSelectedListener != null) {
                            boolean result = mSiteSelectedListener.onSiteLongClick(getItem(clickedPosition));
                            setItemSelected(clickedPosition, true);
                            return result;
                        }
                    } else {
                        AppLog.w(AppLog.T.MAIN, "site picker > invalid clicked position " + clickedPosition);
                    }
                    return false;
                }
            });
        }

        if (mIsSingleItemSelectionEnabled) {
            if (getSitesCount() <= 1) {
                holder.selectedRadioButton.setVisibility(View.GONE);
            } else {
                holder.selectedRadioButton.setVisibility(View.VISIBLE);
                holder.selectedRadioButton.setChecked(mSelectedItemPos == position);
                holder.layoutContainer.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectSingleItem(holder.getAdapterPosition());
                    }
                });
            }
        } else {
            if (holder.selectedRadioButton != null) {
                holder.selectedRadioButton.setVisibility(View.GONE);
            }
        }
    }

    private void selectSingleItem(final int newItemPosition) {
        // clear last selected item
        notifyItemChanged(mSelectedItemPos);
        mSelectedItemPos = newItemPosition;
        // select new item
        notifyItemChanged(mSelectedItemPos);
    }

    public void setSingleItemSelectionEnabled(final boolean enabled) {
        if (enabled != mIsSingleItemSelectionEnabled) {
            mIsSingleItemSelectionEnabled = enabled;
            notifyDataSetChanged();
        }
    }

    public void findAndSelect(final int lastUsedBlogLocalId) {
        int positionInSitesArray = mSites.indexOfSiteId(lastUsedBlogLocalId);
        if (positionInSitesArray != -1) {
            selectSingleItem(positionInSitesArray + getPositionOffset());
        }
    }

    public int getSelectedItemLocalId() {
        return mSites.size() != 0 ? getItem(mSelectedItemPos).localId : -1;
    }

    public String getLastSearch() {
        return mLastSearch;
    }

    public void setLastSearch(String lastSearch) {
        mLastSearch = lastSearch;
    }

    public boolean getIsInSearchMode() {
        return mIsInSearchMode;
    }

    public void searchSites(String searchText) {
        mLastSearch = searchText;
        mSites = filteredSitesByText(mAllSites);

        notifyDataSetChanged();
    }

    private boolean isValidPosition(int position) {
        return (position >= 0 && position < mSites.size());
    }

    /*
     * called when the user chooses to edit the visibility of wp.com blogs
     */
    void setEnableEditMode(boolean enable) {
        if (mIsMultiSelectEnabled == enable) return;

        if (enable) {
            mShowHiddenSites = true;
            mShowSelfHostedSites = false;
        } else {
            mShowHiddenSites = false;
            mShowSelfHostedSites = true;
        }

        mIsMultiSelectEnabled = enable;
        mSelectedPositions.clear();

        loadSites();
    }

    int getNumSelected() {
        return mSelectedPositions.size();
    }

    int getNumHiddenSelected() {
        int numHidden = 0;
        for (Integer i: mSelectedPositions) {
            if (isValidPosition(i) && mSites.get(i).isHidden) {
                numHidden++;
            }
        }
        return numHidden;
    }

    int getNumVisibleSelected() {
        int numVisible = 0;
        for (Integer i: mSelectedPositions) {
            if (i < mSites.size() && !mSites.get(i).isHidden) {
                numVisible++;
            }
        }
        return numVisible;
    }

    private void toggleSelection(int position) {
        setItemSelected(position, !isItemSelected(position));
    }

    private boolean isItemSelected(int position) {
        return mSelectedPositions.contains(position);
    }

    private void setItemSelected(int position, boolean isSelected) {
        if (isItemSelected(position) == isSelected) {
            return;
        }

        if (isSelected) {
            mSelectedPositions.add(position);
        } else {
            mSelectedPositions.remove(position);
        }
        notifyItemChanged(position);

        if (mSelectedCountListener != null) {
            mSelectedCountListener.onSelectedCountChanged(getNumSelected());
        }
    }

    void selectAll() {
        if (mSelectedPositions.size() == mSites.size()) return;

        mSelectedPositions.clear();
        for (int i = 0; i < mSites.size(); i++) {
            mSelectedPositions.add(i);
        }
        notifyDataSetChanged();

        if (mSelectedCountListener != null) {
            mSelectedCountListener.onSelectedCountChanged(getNumSelected());
        }
    }

    void deselectAll() {
        if (mSelectedPositions.size() == 0) return;

        mSelectedPositions.clear();
        notifyDataSetChanged();

        if (mSelectedCountListener != null) {
            mSelectedCountListener.onSelectedCountChanged(getNumSelected());
        }
    }

    private SiteList getSelectedSites() {
        SiteList sites = new SiteList();
        if (!mIsMultiSelectEnabled) {
            return sites;
        }

        for (Integer position : mSelectedPositions) {
            if (isValidPosition(position))
                sites.add(mSites.get(position));
        }

        return sites;
    }

    SiteList getHiddenSites() {
        SiteList hiddenSites = new SiteList();
        for (SiteRecord site: mSites) {
            if (site.isHidden) {
                hiddenSites.add(site);
            }
        }

        return hiddenSites;
    }

    Set<SiteRecord> setVisibilityForSelectedSites(boolean makeVisible) {
        SiteList sites = getSelectedSites();
        Set<SiteRecord> siteRecordSet = new HashSet<>();
        if (sites != null && sites.size() > 0) {
            for (SiteRecord site: sites) {
                int index = mAllSites.indexOfSite(site);
                if (index > -1) {
                    SiteRecord siteRecord = mAllSites.get(index);
                    if (siteRecord.isHidden == makeVisible) {
                        // add it to change set
                        siteRecordSet.add(siteRecord);
                    }
                    siteRecord.isHidden = !makeVisible;
                }
            }
        }
        notifyDataSetChanged();
        return siteRecordSet;
    }

    public void loadSites() {
        new LoadSitesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private SiteList filteredSitesByTextIfInSearchMode(SiteList sites) {
        if (!mIsInSearchMode) {
            return sites;
        } else {
            return filteredSitesByText(sites);
        }
    }

    private SiteList filteredSitesByText(SiteList sites) {
        SiteList filteredSiteList = new SiteList();

        for (int i = 0; i < sites.size(); i++) {
            SiteRecord record = sites.get(i);
            String siteNameLowerCase = record.blogName.toLowerCase();
            String hostNameLowerCase = record.homeURL.toLowerCase();

            if (siteNameLowerCase.contains(mLastSearch.toLowerCase()) || hostNameLowerCase.contains(mLastSearch.toLowerCase())) {
                filteredSiteList.add(record);
            }
        }

        return filteredSiteList;
    }

    /*
     * AsyncTask which loads sites from database and populates the adapter
     */
    private class LoadSitesTask extends AsyncTask<Void, Void, SiteList[]> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (mDataLoadedListener != null) {
                boolean isEmpty = mSites == null || mSites.size() == 0;
                mDataLoadedListener.onBeforeLoad(isEmpty);
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
        }

        @Override
        protected SiteList[] doInBackground(Void... params) {
            List<SiteModel> siteModels;
            if (mIsInSearchMode) {
                siteModels = mSiteStore.getSites();
            } else {
                siteModels = getBlogsForCurrentView();
            }

            if (mIgnoreSitesIds != null) {
                List<SiteModel> unignoredSiteModels = new ArrayList<>();
                for (SiteModel site : siteModels) {
                    if (!mIgnoreSitesIds.contains(site.getId())) {
                        unignoredSiteModels.add(site);
                    }
                }
                siteModels = unignoredSiteModels;
            }

            SiteList sites = new SiteList(siteModels);

            // sort primary blog to the top, otherwise sort by blog/host
            final long primaryBlogId = mAccountStore.getAccount().getPrimarySiteId();
            Collections.sort(sites, new Comparator<SiteRecord>() {
                public int compare(SiteRecord site1, SiteRecord site2) {
                    if (primaryBlogId > 0 && !mIsInSearchMode) {
                        if (site1.siteId == primaryBlogId) {
                            return -1;
                        } else if (site2.siteId == primaryBlogId) {
                            return 1;
                        }
                    }
                    return site1.getBlogNameOrHomeURL().compareToIgnoreCase(site2.getBlogNameOrHomeURL());
                }
            });

            // flag recently-picked sites and move them to the top if there are enough sites and
            // the user isn't searching
            if (!mIsInSearchMode && sites.size() >= RECENTLY_PICKED_THRESHOLD) {
                ArrayList<Integer> pickedIds = AppPrefs.getRecentlyPickedSiteIds();
                for (int i = pickedIds.size() - 1; i > -1; i--) {
                    int thisId = pickedIds.get(i);
                    int indexOfSite = sites.indexOfSiteId(thisId);
                    if (indexOfSite > -1) {
                        SiteRecord site = sites.remove(indexOfSite);
                        site.isRecentPick = true;
                        sites.add(0, site);
                    }
                }
            }

            if (mSites == null || !mSites.isSameList(sites)) {
                SiteList allSites = (SiteList) sites.clone();
                SiteList filteredSites = filteredSitesByTextIfInSearchMode(sites);

                return new SiteList[]{allSites, filteredSites};
            }

            return null;
        }

        @Override
        protected void onPostExecute(SiteList[] updatedSiteLists) {
            if (updatedSiteLists != null) {
                mAllSites = updatedSiteLists[0];
                mSites = updatedSiteLists[1];
                notifyDataSetChanged();
            }
            if (mDataLoadedListener != null) {
                mDataLoadedListener.onAfterLoad();
            }
        }

        private List<SiteModel> getBlogsForCurrentView() {
            if (mShowHiddenSites) {
                if (mShowSelfHostedSites) {
                    return mSiteStore.getSites();
                } else {
                    return mSiteStore.getSitesAccessedViaWPComRest();
                }
            } else {
                if (mShowSelfHostedSites) {
                    List<SiteModel> out = mSiteStore.getVisibleSitesAccessedViaWPCom();
                    out.addAll(mSiteStore.getSitesAccessedViaXMLRPC());
                    return out;
                } else {
                    return mSiteStore.getVisibleSitesAccessedViaWPCom();
                }
            }
        }
    }

    /**
     * SiteRecord is a simplified version of the full account (blog) record
     */
     static class SiteRecord {
        final int localId;
        final long siteId;
        final String blogName;
        final String homeURL;
        final String url;
        final String blavatarUrl;
        boolean isHidden;
        boolean isRecentPick;

        SiteRecord(SiteModel siteModel) {
            localId = siteModel.getId();
            siteId = siteModel.getSiteId();
            blogName = SiteUtils.getSiteNameOrHomeURL(siteModel);
            homeURL = SiteUtils.getHomeURLOrHostName(siteModel);
            url = siteModel.getUrl();
            blavatarUrl = SiteUtils.getSiteIconUrl(siteModel, mBlavatarSz);
            isHidden = !siteModel.isVisible();
        }

        String getBlogNameOrHomeURL() {
            if (TextUtils.isEmpty(blogName)) {
                return homeURL;
            }
            return blogName;
        }
    }

    static class SiteList extends ArrayList<SiteRecord> {
        SiteList() { }
        SiteList(List<SiteModel> siteModels) {
            if (siteModels != null) {
                for (SiteModel siteModel : siteModels) {
                    add(new SiteRecord(siteModel));
                }
            }
        }

        boolean isSameList(SiteList sites) {
            if (sites == null || sites.size() != this.size()) {
                return false;
            }
            int i;
            for (SiteRecord site: sites) {
                i = indexOfSite(site);
                if (i == -1
                        || this.get(i).isHidden != site.isHidden
                        || this.get(i).isRecentPick != site.isRecentPick) {
                    return false;
                }
            }
            return true;
        }

        int indexOfSite(SiteRecord site) {
            if (site != null && site.siteId > 0) {
                for (int i = 0; i < size(); i++) {
                    if (site.siteId == this.get(i).siteId) {
                        return i;
                    }
                }
            }
            return -1;
        }

        int indexOfSiteId(int localId) {
            for (int i = 0; i < size(); i++) {
                if (localId == this.get(i).localId) {
                    return i;
                }
            }
            return -1;
        }
    }
}
